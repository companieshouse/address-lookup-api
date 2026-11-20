-- Jira: ALS-149
-- Purpose: Add the FULL import procedure that loads Landing CSVs into the FULL staging tables.
-- Author/team: Address Lookup Service
-- Date: 2026-11-19
-- Flyway only installs these objects. The Supply Pipeline runs them, e.g.
--   CALL os_ingest.import_version('20780', '130989', 'landing-bucket', 'eu-west-2');
-- Requires the aws_s3 and aws_commons extensions and an Aurora S3 import role (Aurora only).
-- Run it on a direct cluster connection (not RDS Proxy) with statement_timeout and
-- idle_in_transaction_session_timeout disabled: it is one transaction that can run for hours.

-- Reads one JSON object from Landing. aws_s3 can only import table rows, so the file is loaded
-- one line per row using delimiter and quote bytes that cannot occur in JSON text.
CREATE FUNCTION os_ingest.read_landing_json(p_bucket text, p_key text, p_region text)
RETURNS jsonb
LANGUAGE plpgsql
AS $$
DECLARE
    v_json jsonb;
BEGIN
    DROP TABLE IF EXISTS pg_temp.landing_json_lines;
    CREATE TEMPORARY TABLE landing_json_lines (line text) ON COMMIT DROP;
    PERFORM aws_s3.table_import_from_s3(
        'pg_temp.landing_json_lines', 'line',
        '(format csv, delimiter E''\x02'', quote E''\x01'', encoding ''UTF8'')',
        p_bucket, p_key, p_region);
    SELECT string_agg(line, E'\n' ORDER BY ctid)::jsonb INTO v_json FROM pg_temp.landing_json_lines;
    IF v_json IS NULL THEN
        RAISE EXCEPTION 'Landing file s3://%/% is empty', p_bucket, p_key;
    END IF;
    RETURN v_json;
END
$$;

-- Imports one FULL data package from Landing into the FULL staging tables, one CSV per dataset.
-- Landing layout: the manifest is read from p_manifest_key and each CSV from p_csv_prefix||<dataset>.csv.
-- The defaults are full/<data_package>/<version_id>/manifest.json and full/<data_package>/<version_id>/;
-- pass the keys the Supply Pipeline actually wrote if they differ. The Supply Pipeline has already
-- checked that the manifest and every CSV are tagged NO_THREATS_FOUND, because aws_s3 cannot read
-- object tags. The manifest's schemaVersion and crs must match os_control.schema_contract and the
-- SRID of the active geometry column, and each CSV header must match the table's columns.
-- The whole call is one transaction, so a failure (including a failed completeness check) leaves
-- nothing behind. It can be re-run: the release's manifest and ledger rows are replaced and the
-- FULL staging tables are recreated empty. Releases already in release_history are rejected.
CREATE PROCEDURE os_ingest.import_version(
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
    FOR v_dataset, v_contract_version IN
        SELECT dataset, version FROM os_control.schema_contract ORDER BY dataset
    LOOP
        IF v_manifest -> v_dataset -> 'summary' -> 'recordCount' IS NULL THEN
            RAISE EXCEPTION 'Manifest has no recordCount for dataset %', v_dataset;
        END IF;
        IF v_manifest -> v_dataset -> 'summary' ->> 'schemaVersion' IS DISTINCT FROM v_contract_version THEN
            RAISE EXCEPTION 'Dataset % has schemaVersion %, the contract is %', v_dataset,
                v_manifest -> v_dataset -> 'summary' ->> 'schemaVersion', v_contract_version;
        END IF;
        SELECT g.srid INTO v_srid FROM public.geometry_columns g
        WHERE g.f_table_schema = 'os_data' AND g.f_table_name = v_dataset || '_v2'
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
    FROM os_control.schema_contract c;

    FOR v_dataset IN SELECT dataset FROM os_control.schema_contract ORDER BY dataset LOOP
        v_file := v_dataset || '.csv';
        v_stage := format('os_stage_full.%I', v_dataset || '_v2');

        EXECUTE format('DROP TABLE IF EXISTS %s', v_stage);
        EXECUTE format(
            'CREATE TABLE %s (LIKE %I.%I EXCLUDING ALL)', v_stage, 'os_data', v_dataset || '_v2');
        -- Owned like the active table, so any login in the owner role can continue the load.
        SELECT pg_get_userbyid(c.relowner) INTO v_owner
        FROM pg_class c WHERE c.oid = format('os_data.%I', v_dataset || '_v2')::regclass;
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
