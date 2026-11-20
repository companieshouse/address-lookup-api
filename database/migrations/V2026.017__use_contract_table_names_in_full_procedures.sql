-- Jira: ALS-143
-- Purpose: Make the FULL procedures resolve each dataset's active table from the schema contract.
-- Author/team: Address Lookup Service
-- Date: 2026-11-20
-- V2026.011-013 derived the active table as dataset || '_v2'. The built address and pre-build
-- address datasets (schema 3.0) have no _v2 suffix, so the procedures now read
-- os_control.schema_contract.active_table. Logic is otherwise unchanged from those migrations.
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
AS $$
DECLARE
    v_csv_prefix text := COALESCE(p_csv_prefix, format('full/%s/%s/', p_data_package, p_version_id));
    v_manifest_key text := COALESCE(p_manifest_key, v_csv_prefix || 'manifest.json');
    v_manifest jsonb;
    v_release_id text;
    v_dataset text;
    v_table text;
    v_file text;
    v_stage text;
    v_columns text;
    v_rows bigint;
    v_problems text;
    v_contract_version text;
    v_srid integer;
    v_crs text;
    v_latest_target date;
    v_owner text;
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
        SELECT dataset, version, active_table FROM os_control.schema_contract
        WHERE enabled ORDER BY dataset
    LOOP
        IF v_manifest -> v_dataset -> 'summary' -> 'recordCount' IS NULL THEN
            RAISE EXCEPTION 'Manifest has no recordCount for dataset %', v_dataset;
        END IF;
        IF v_manifest -> v_dataset -> 'summary' ->> 'schemaVersion' IS DISTINCT FROM v_contract_version THEN
            RAISE EXCEPTION 'Dataset % has schemaVersion %, the contract is %', v_dataset,
                v_manifest -> v_dataset -> 'summary' ->> 'schemaVersion', v_contract_version;
        END IF;
        SELECT g.srid INTO v_srid FROM public.geometry_columns g
        WHERE g.f_table_schema = 'os_data' AND g.f_table_name = v_table
            AND g.f_geometry_column = 'geometry';
        v_crs := v_manifest -> v_dataset -> 'summary' ->> 'crs';
        IF v_crs IS NULL OR substring(v_crs FROM '(\d+)$') IS DISTINCT FROM v_srid::text THEN
            RAISE EXCEPTION 'Dataset % has crs %, the active table uses EPSG:%', v_dataset, v_crs, v_srid;
        END IF;
    END LOOP;

    DELETE FROM os_stage.import_ledger WHERE release_id = v_release_id;
    DELETE FROM os_stage.data_package_manifest WHERE release_id = v_release_id;
    INSERT INTO os_stage.data_package_manifest (
        release_id, mode, target, previous, package_id, version_id, datasets
    )
    SELECT
        v_release_id, 'FULL', v_manifest ->> 'target', NULL, p_data_package, p_version_id,
        jsonb_agg(
            jsonb_build_object(
                'dataset', c.dataset,
                'file', c.dataset || '.csv',
                'rows', (v_manifest -> c.dataset -> 'summary' ->> 'recordCount')::bigint
            ) ORDER BY c.dataset)
    FROM os_control.schema_contract c
    WHERE c.enabled;

    FOR v_dataset, v_table IN
        SELECT dataset, active_table FROM os_control.schema_contract
        WHERE enabled ORDER BY dataset
    LOOP
        v_file := v_dataset || '.csv';
        v_stage := format('os_stage_full.%I', v_table);

        EXECUTE format('DROP TABLE IF EXISTS %s', v_stage);
        EXECUTE format(
            'CREATE TABLE %s (LIKE %I.%I EXCLUDING ALL)', v_stage, 'os_data', v_table);
        -- Owned like the active table, so any login in the owner role can continue the load.
        SELECT pg_get_userbyid(c.relowner) INTO v_owner
        FROM pg_class c WHERE c.oid = format('os_data.%I', v_table)::regclass;
        EXECUTE format('ALTER TABLE %s OWNER TO %I', v_stage, v_owner);
        EXECUTE format(
            'ALTER TABLE %s ALTER COLUMN release_id SET DEFAULT %L', v_stage, v_release_id);

        SELECT string_agg(quote_ident(attname), ',' ORDER BY attnum) INTO v_columns
        FROM pg_attribute
        WHERE attrelid = v_stage::regclass AND attnum > 0 AND NOT attisdropped
            AND attname <> 'release_id';

        PERFORM aws_s3.table_import_from_s3(
            v_stage, v_columns, '(format csv, header match, encoding ''UTF8'')',
            p_bucket, v_csv_prefix || v_file, p_region);
        -- release_id only exists to fill the column during the load; a default left on the table
        -- would silently stamp rows written later with this release.
        EXECUTE format('ALTER TABLE %s ALTER COLUMN release_id DROP DEFAULT', v_stage);

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

CREATE OR REPLACE PROCEDURE os_ingest.build_indexes(p_release_id text)
LANGUAGE plpgsql
SET lock_timeout = '60s'
SET maintenance_work_mem = '1GB'
AS $$
DECLARE
    v_def record;
    v_table text;
BEGIN
    PERFORM pg_advisory_xact_lock(os_ingest.import_lock_key());

    IF NOT os_ingest.is_release_complete(p_release_id) THEN
        RAISE EXCEPTION 'Release % has not been imported completely', p_release_id;
    END IF;

    FOR v_def IN
        SELECT d.dataset, d.index_name, d.index_type, d.definition,
            split_part(d.table_name, '.', 2) AS table_name
        FROM os_control.index_definition d
        ORDER BY d.dataset, (d.index_type <> 'primary_key'), d.index_name
    LOOP
        IF v_def.index_type = 'primary_key' THEN
            IF NOT EXISTS (
                SELECT 1 FROM pg_constraint
                WHERE conrelid = format('os_stage_full.%I', v_def.table_name)::regclass
                    AND conname = v_def.index_name
            ) THEN
                EXECUTE format(
                    'ALTER TABLE os_stage_full.%I ADD CONSTRAINT %I PRIMARY KEY (%s)',
                    v_def.table_name, v_def.index_name, v_def.definition);
            END IF;
        ELSE
            EXECUTE format(
                'CREATE INDEX IF NOT EXISTS %I ON os_stage_full.%I (%s)',
                v_def.index_name, v_def.table_name, v_def.definition);
        END IF;
    END LOOP;

    FOR v_table IN
        SELECT active_table FROM os_control.schema_contract WHERE enabled ORDER BY dataset
    LOOP
        EXECUTE format('ANALYZE os_stage_full.%I', v_table);
    END LOOP;
END
$$;

CREATE OR REPLACE PROCEDURE os_ingest.promote(p_release_id text)
LANGUAGE plpgsql
SET search_path = pg_catalog, pg_temp
SET lock_timeout = '3s'
AS $$
DECLARE
    v_manifest os_stage.data_package_manifest%ROWTYPE;
    v_dataset text;
    v_table text;
    v_active text;
    v_stage text;
    v_expected bigint;
    v_actual bigint;
    v_view_definition text;
    v_acl record;
    v_owner text;
    v_latest_target date;
    v_row_counts jsonb;
BEGIN
    PERFORM pg_advisory_xact_lock(os_ingest.import_lock_key());
    PERFORM pg_advisory_xact_lock(os_ingest.release_lock_key());

    IF EXISTS (SELECT 1 FROM os_data.release_history WHERE release_id = p_release_id) THEN
        RAISE NOTICE 'Release % is already promoted', p_release_id;
        RETURN;
    END IF;

    SELECT * INTO v_manifest
    FROM os_stage.data_package_manifest WHERE release_id = p_release_id;
    IF NOT FOUND OR v_manifest.mode <> 'FULL' THEN
        RAISE EXCEPTION 'Release % is not an imported FULL release', p_release_id;
    END IF;
    IF NOT os_ingest.is_release_complete(p_release_id) THEN
        RAISE EXCEPTION 'Release % has not been imported completely', p_release_id;
    END IF;
    SELECT max(h.target::date) INTO v_latest_target FROM os_data.release_history h;
    IF v_latest_target IS NOT NULL AND v_manifest.target::date < v_latest_target THEN
        RAISE EXCEPTION 'Release % targets %, older than the active data (%)',
            p_release_id, v_manifest.target, v_latest_target;
    END IF;

    FOR v_dataset, v_table IN
        SELECT dataset, active_table FROM os_control.schema_contract
        WHERE enabled ORDER BY dataset
    LOOP
        v_stage := format('os_stage_full.%I', v_table);
        IF to_regclass(v_stage) IS NULL THEN
            RAISE EXCEPTION 'FULL staging table % does not exist', v_stage;
        END IF;
        EXECUTE format('SELECT count(*) FROM %s WHERE release_id = %L', v_stage, p_release_id)
            INTO v_actual;
        SELECT l.rows INTO v_expected FROM os_stage.import_ledger l
        WHERE l.release_id = p_release_id AND l.dataset = v_dataset;
        IF v_actual = 0 THEN
            RAISE EXCEPTION 'FULL staging table % is empty for release %', v_stage, p_release_id;
        END IF;
        IF v_actual IS DISTINCT FROM v_expected THEN
            RAISE EXCEPTION 'FULL staging table % holds % rows for release %, expected %',
                v_stage, v_actual, p_release_id, v_expected;
        END IF;
        IF EXISTS (
            SELECT 1 FROM os_control.index_definition d
            WHERE d.dataset = v_dataset AND NOT EXISTS (
                SELECT 1 FROM pg_indexes i
                WHERE i.schemaname = 'os_stage_full' AND i.indexname = d.index_name)
        ) THEN
            RAISE EXCEPTION 'Indexes are missing on %; run build_indexes first', v_stage;
        END IF;
    END LOOP;

    LOCK TABLE address_lookup.address_lookup IN ACCESS EXCLUSIVE MODE;
    v_view_definition := pg_get_viewdef('address_lookup.address_lookup'::regclass);

    FOR v_dataset, v_table IN
        SELECT dataset, active_table FROM os_control.schema_contract
        WHERE enabled ORDER BY dataset
    LOOP
        v_active := format('os_data.%I', v_table);
        v_stage := format('os_stage_full.%I', v_table);

        EXECUTE format('LOCK TABLE %s IN ACCESS EXCLUSIVE MODE', v_active);

        SELECT pg_get_userbyid(c.relowner) INTO v_owner FROM pg_class c WHERE c.oid = v_active::regclass;
        EXECUTE format('ALTER TABLE %s OWNER TO %I', v_stage, v_owner);
        FOR v_acl IN
            SELECT CASE a.grantee WHEN 0 THEN 'PUBLIC' ELSE quote_ident(pg_get_userbyid(a.grantee)) END
                AS grantee
            FROM pg_class c, aclexplode(c.relacl) a
            WHERE c.oid = v_stage::regclass AND a.grantee <> c.relowner
        LOOP
            EXECUTE format('REVOKE ALL ON %s FROM %s', v_stage, v_acl.grantee);
        END LOOP;
        FOR v_acl IN
            SELECT CASE a.grantee WHEN 0 THEN 'PUBLIC' ELSE quote_ident(pg_get_userbyid(a.grantee)) END
                AS grantee,
                a.privilege_type, a.is_grantable
            FROM pg_class c, aclexplode(c.relacl) a
            WHERE c.oid = v_active::regclass AND a.grantee <> c.relowner
        LOOP
            EXECUTE format('GRANT %s ON %s TO %s%s', v_acl.privilege_type, v_stage, v_acl.grantee,
                CASE WHEN v_acl.is_grantable THEN ' WITH GRANT OPTION' ELSE '' END);
        END LOOP;

        EXECUTE format('DROP TABLE IF EXISTS os_prev.%I', v_table);
        EXECUTE format('ALTER TABLE %s SET SCHEMA os_prev', v_active);
        EXECUTE format('ALTER TABLE %s SET SCHEMA os_data', v_stage);
    END LOOP;

    EXECUTE format(
        'CREATE OR REPLACE VIEW address_lookup.address_lookup AS %s',
        rtrim(v_view_definition, E';\n '));

    SELECT jsonb_object_agg(l.dataset, l.rows) INTO v_row_counts
    FROM os_stage.import_ledger l WHERE l.release_id = p_release_id;

    INSERT INTO os_data.release_history (release_id, previous, mode, target, row_counts)
    VALUES (p_release_id, NULL, 'FULL', v_manifest.target, v_row_counts);
END
$$;
