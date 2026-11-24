-- Jira: ALS-149
-- Purpose: Build FULL geometry from the coordinates and enable all six datasets for the FULL load.
-- Author/team: Address Lookup Service
-- Date: 2026-11-24
-- ALS-149 v2: geometry is built from easting and northing for EPSG:27700 (GB) and from longitude
-- and latitude for EPSG:4258 (ISL), following the OS DDL, instead of being taken from the CSV's
-- geometry column. import_version now loads that column as text and replaces it with the point.
-- The schema 3.0 datasets registered disabled by V2026.016 are enabled: the dataset catalogue and
-- Discovery now include them, so FULL manifests carry their recordCount. Deploy the Lambdas that
-- carry that support before or with this migration, or import_version rejects the manifest.
-- import_version is otherwise unchanged from V2026.017.

-- Replaces the text geometry column of a FULL staging table with a point built from the dataset's
-- coordinate columns. A null coordinate leaves a null point, which the NOT NULL column rejects.
-- Rewrites the table once (ALTER COLUMN ... TYPE).
CREATE FUNCTION os_ingest.build_full_geometry(p_stage regclass, p_srid integer)
    RETURNS void
    LANGUAGE plpgsql
AS
$$
DECLARE
    v_x text;
    v_y text;
BEGIN
    CASE p_srid
        WHEN 27700 THEN v_x := 'easting'; v_y := 'northing';
        WHEN 4258 THEN v_x := 'longitude'; v_y := 'latitude';
        ELSE RAISE EXCEPTION 'No coordinate columns are defined for EPSG:%', p_srid;
        END CASE;
    EXECUTE format(
            'ALTER TABLE %s ALTER COLUMN geometry TYPE public.geometry(Point, %s) '
                || 'USING public.ST_SetSRID(public.ST_MakePoint('
                || '%I::double precision, %I::double precision), %s)',
            p_stage, p_srid, v_x, v_y, p_srid);
END
$$;

COMMENT ON FUNCTION os_ingest.build_full_geometry(regclass, integer) IS
    'Builds the geometry of a FULL staging table from easting/northing (27700) or longitude/latitude (4258).';

CREATE OR REPLACE PROCEDURE os_ingest.import_version(
    p_data_package text,
    p_version_id text,
    p_bucket text,
    p_region text,
    p_manifest_key text DEFAULT NULL,
    p_csv_prefix text DEFAULT NULL
)
    LANGUAGE plpgsql
    SET lock_timeout = '60s'
AS
$$
DECLARE
    v_csv_prefix       text := COALESCE(p_csv_prefix, format('full/%s/%s/', p_data_package, p_version_id));
    v_manifest_key     text := COALESCE(p_manifest_key, v_csv_prefix || 'manifest.json');
    v_manifest         jsonb;
    v_release_id       text;
    v_dataset          text;
    v_table            text;
    v_file             text;
    v_stage            text;
    v_columns          text;
    v_rows             bigint;
    v_problems         text;
    v_contract_version text;
    v_srid             integer;
    v_crs              text;
    v_latest_target    date;
    v_owner            text;
BEGIN
    PERFORM pg_advisory_xact_lock(os_ingest.import_lock_key());

    v_manifest := os_ingest.read_landing_json(p_bucket, v_manifest_key, p_region);
    v_release_id := v_manifest ->> 'releaseId';

    IF v_release_id IS NULL OR v_manifest ->> 'mode' IS DISTINCT FROM 'FULL' THEN
        RAISE EXCEPTION 'Manifest is not a FULL release manifest';
    END IF;
    IF v_manifest ->> 'dataPackageId' IS DISTINCT FROM p_data_package
        OR v_manifest ->> 'versionId' IS DISTINCT FROM p_version_id THEN
        RAISE EXCEPTION 'Manifest is for data package % version %, not % version %',
            v_manifest ->> 'dataPackageId', v_manifest ->> 'versionId',
            p_data_package, p_version_id;
    END IF;
    IF EXISTS (SELECT 1 FROM os_data.release_history WHERE release_id = v_release_id) THEN
        RAISE EXCEPTION 'Release % is already in release_history', v_release_id;
    END IF;
    SELECT max(h.target::date) INTO v_latest_target FROM os_data.release_history h;
    IF v_latest_target IS NOT NULL AND (v_manifest ->> 'target')::date < v_latest_target THEN
        RAISE EXCEPTION 'Release % targets %, older than the active data (%)',
            v_release_id, v_manifest ->> 'target', v_latest_target;
    END IF;
    FOR v_dataset, v_contract_version, v_table IN
        SELECT dataset, version, active_table
        FROM os_control.schema_contract
        WHERE enabled
        ORDER BY dataset
        LOOP
            IF v_manifest -> v_dataset -> 'summary' -> 'recordCount' IS NULL THEN
                RAISE EXCEPTION 'Manifest has no recordCount for dataset %', v_dataset;
            END IF;
            IF v_manifest -> v_dataset -> 'summary' ->> 'schemaVersion' IS DISTINCT FROM v_contract_version THEN
                RAISE EXCEPTION 'Dataset % has schemaVersion %, the contract is %', v_dataset,
                    v_manifest -> v_dataset -> 'summary' ->> 'schemaVersion', v_contract_version;
            END IF;
            SELECT g.srid
            INTO v_srid
            FROM public.geometry_columns g
            WHERE g.f_table_schema = 'os_data'
              AND g.f_table_name = v_table
              AND g.f_geometry_column = 'geometry';
            v_crs := v_manifest -> v_dataset -> 'summary' ->> 'crs';
            IF v_crs IS NULL OR substring(v_crs FROM '(\d+)$') IS DISTINCT FROM v_srid::text THEN
                RAISE EXCEPTION 'Dataset % has crs %, the active table uses EPSG:%', v_dataset, v_crs, v_srid;
            END IF;
        END LOOP;

    DELETE FROM os_stage.import_ledger WHERE release_id = v_release_id;
    DELETE FROM os_stage.data_package_manifest WHERE release_id = v_release_id;
    INSERT INTO os_stage.data_package_manifest (release_id, mode, target, previous, package_id, version_id, datasets)
    SELECT v_release_id,
           'FULL',
           v_manifest ->> 'target',
           NULL,
           p_data_package,
           p_version_id,
           jsonb_agg(
                   jsonb_build_object(
                           'dataset', c.dataset,
                           'file', c.dataset || '.csv',
                           'rows', (v_manifest -> c.dataset -> 'summary' ->> 'recordCount')::bigint
                   ) ORDER BY c.dataset)
    FROM os_control.schema_contract c
    WHERE c.enabled;

    FOR v_dataset, v_table IN
        SELECT dataset, active_table
        FROM os_control.schema_contract
        WHERE enabled
        ORDER BY dataset
        LOOP
            v_file := v_dataset || '.csv';
            v_stage := format('os_stage_full.%I', v_table);
            SELECT g.srid
            INTO v_srid
            FROM public.geometry_columns g
            WHERE g.f_table_schema = 'os_data'
              AND g.f_table_name = v_table
              AND g.f_geometry_column = 'geometry';

            EXECUTE format('DROP TABLE IF EXISTS %s', v_stage);
            EXECUTE format(
                    'CREATE TABLE %s (LIKE %I.%I EXCLUDING ALL)', v_stage, 'os_data', v_table);
-- Owned like the active table, so any login in the owner role can continue the load.
            SELECT pg_get_userbyid(c.relowner)
            INTO v_owner
            FROM pg_class c
            WHERE c.oid = format('os_data.%I', v_table)::regclass;
            EXECUTE format('ALTER TABLE %s OWNER TO %I', v_stage, v_owner);
            EXECUTE format(
                    'ALTER TABLE %s ALTER COLUMN release_id SET DEFAULT %L', v_stage, v_release_id);
-- The CSV geometry is loaded as text and then replaced by build_full_geometry.
            EXECUTE format('ALTER TABLE %s ALTER COLUMN geometry TYPE text', v_stage);

            SELECT string_agg(quote_ident(attname), ',' ORDER BY attnum)
            INTO v_columns
            FROM pg_attribute
            WHERE attrelid = v_stage::regclass
              AND attnum > 0
              AND NOT attisdropped
              AND attname <> 'release_id';

            PERFORM aws_s3.table_import_from_s3(
                    v_stage, v_columns, '(format csv, header match, encoding ''UTF8'')',
                    p_bucket, v_csv_prefix || v_file, p_region);
            -- release_id only exists to fill the column during the load; a default left on the table
            -- would silently stamp rows written later with this release.
            EXECUTE format('ALTER TABLE %s ALTER COLUMN release_id DROP DEFAULT', v_stage);
            PERFORM os_ingest.build_full_geometry(v_stage::regclass, v_srid);

            EXECUTE format('SELECT count(*) FROM %s', v_stage) INTO v_rows;
            INSERT INTO os_stage.import_ledger (release_id, dataset, file, rows)
            VALUES (v_release_id, v_dataset, v_file, v_rows);
            RAISE NOTICE 'Imported % rows into %', v_rows, v_stage;
        END LOOP;

    IF NOT os_ingest.is_release_complete(v_release_id) THEN
        SELECT string_agg(
                       format('%s/%s: %s (expected %s, loaded %s)',
                              c.dataset, c.file, c.status, c.expected_rows, c.loaded_rows), '; ')
        INTO v_problems
        FROM os_ingest.check_release_completeness(v_release_id) c
        WHERE c.status <> 'OK';
        RAISE EXCEPTION 'Completeness check failed for release %: %', v_release_id, v_problems;
    END IF;
END
$$;

UPDATE os_control.schema_contract
SET enabled = true
WHERE dataset IN (
                  'add_gb_builtaddress', 'add_gb_prebuildaddress',
                  'add_isl_builtaddress', 'add_isl_prebuildaddress'
    );
