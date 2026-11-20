-- Jira: ALS-149
-- Purpose: Add the FULL promotion procedure that swaps the FULL staging tables into active.
-- Author/team: Address Lookup Service
-- Date: 2026-11-19
-- Run after build_indexes, e.g. CALL os_ingest.promote('<release_id>');
-- One transaction holding os_ingest.release_lock_key(), the advisory lock the Upload COU apply
-- also takes. The active tables move to os_prev (replacing the previous generation, kept for
-- rollback), the staging tables move to os_data, the lookup view is re-pointed and the release
-- is recorded in release_history as a new chain (previous is NULL). Readers see either the old or
-- the new tables, never a partial load. Re-running an already promoted release does nothing.
-- Lock order matters: API reads lock the view and then its tables, so promote locks the view
-- first (which also locks its tables) and only then moves tables, or it can deadlock with a read.
-- While promote waits for a table lock, new reads queue behind its view lock, so lock_timeout is
-- short: promote fails cleanly (and can be re-run) instead of stalling the API behind a
-- long-running query. It also fails fast if an import or the Upload COU apply holds a lock. The staging tables take the active tables' owner and
-- privileges, so the caller must be a member of the owning role. The fixed search_path keeps
-- the captured view definition independent of the caller's settings.
CREATE PROCEDURE os_ingest.promote(p_release_id text)
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

    FOR v_dataset IN SELECT dataset FROM os_control.schema_contract ORDER BY dataset LOOP
        v_stage := format('os_stage_full.%I', v_dataset || '_v2');
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

    FOR v_dataset IN SELECT dataset FROM os_control.schema_contract ORDER BY dataset LOOP
        v_table := v_dataset || '_v2';
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
