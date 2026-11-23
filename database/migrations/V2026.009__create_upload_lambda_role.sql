-- Jira: ALS-143
-- Purpose: Create a least-privilege group role for the Upload Lambda.
-- Author/team: Address Lookup Service
-- Date: 2026-11-17
-- The als_upload group role is created by db-bootstrap because the schema owner cannot create
-- roles. Login users are provisioned outside Flyway and granted membership of it.
DO $$
BEGIN
    IF NOT EXISTS (SELECT 1 FROM pg_roles WHERE rolname = 'als_upload') THEN
        RAISE NOTICE 'Role als_upload does not exist; skipping Upload Lambda grants';
        RETURN;
    END IF;
    GRANT USAGE ON SCHEMA os_stage TO als_upload;
    GRANT INSERT, SELECT ON os_stage.data_package_manifest, os_stage.import_ledger TO als_upload;
    GRANT INSERT, SELECT ON os_stage.add_gb_royalmailaddress, os_stage.add_isl_royalmailaddress
        TO als_upload;
    GRANT USAGE ON SCHEMA os_ingest TO als_upload;
    GRANT EXECUTE ON FUNCTION os_ingest.check_release_completeness(text),
        os_ingest.is_release_complete(text) TO als_upload;
END
$$;
