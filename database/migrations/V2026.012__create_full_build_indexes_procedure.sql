-- Jira: ALS-149
-- Purpose: Add the FULL index build procedure for the FULL staging tables.
-- Author/team: Address Lookup Service
-- Date: 2026-11-19
-- Run after import_version, e.g. CALL os_ingest.build_indexes('<release_id>');
-- Builds the indexes and primary keys registered in os_control.index_definition on the FULL
-- staging copy of each table, then runs ANALYZE. Re-running skips what already exists.
-- Takes the import lock, so it waits for a running import_version or promote. Like import_version
-- it needs a direct connection with statement_timeout disabled.
CREATE PROCEDURE os_ingest.build_indexes(p_release_id text)
LANGUAGE plpgsql
SET lock_timeout = '60s'
SET maintenance_work_mem = '1GB'
AS $$
DECLARE
    v_def record;
    v_dataset text;
    v_stage text;
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
        v_stage := format('os_stage_full.%I', v_def.table_name);
        IF v_def.index_type = 'primary_key' THEN
            IF NOT EXISTS (
                SELECT 1 FROM pg_constraint
                WHERE conrelid = v_stage::regclass AND conname = v_def.index_name
            ) THEN
                EXECUTE format(
                    'ALTER TABLE %s ADD CONSTRAINT %I PRIMARY KEY (%s)',
                    v_stage, v_def.index_name, v_def.definition);
            END IF;
        ELSE
            EXECUTE format(
                'CREATE INDEX IF NOT EXISTS %I ON %s (%s)',
                v_def.index_name, v_stage, v_def.definition);
        END IF;
    END LOOP;

    FOR v_dataset IN SELECT dataset FROM os_control.schema_contract ORDER BY dataset LOOP
        EXECUTE format('ANALYZE os_stage_full.%I', v_dataset || '_v2');
    END LOOP;
END
$$;
