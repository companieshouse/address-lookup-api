-- Jira: ALS-149
-- Purpose: Create the FULL staging tables and the release advisory lock keys.
-- Author/team: Address Lookup Service
-- Date: 2026-11-19
-- FULL staging lives in its own os_stage_full schema, with the same table names as the active
-- tables, so a daily Upload can run while a FULL is imported and no grants on os_stage apply to it.
-- The tables have the active shape (geometry, release_id) but no indexes or constraints: they are
-- loaded first, indexed by os_ingest.build_indexes, then moved into os_data by os_ingest.promote.
-- os_ingest.import_version recreates them on every run.
CREATE SCHEMA os_stage_full;

CREATE TABLE os_stage_full.add_gb_royalmailaddress_v2 (
    LIKE os_data.add_gb_royalmailaddress_v2 EXCLUDING ALL
);
CREATE TABLE os_stage_full.add_isl_royalmailaddress_v2 (
    LIKE os_data.add_isl_royalmailaddress_v2 EXCLUDING ALL
);

-- Transaction-level advisory lock key that serialises anything that changes the active tables.
-- promote takes it; the Upload COU apply must take the same key with pg_advisory_xact_lock.
-- 4279379 is the ASCII bytes of 'ALS' (0x414C53).
CREATE FUNCTION os_ingest.release_lock_key()
RETURNS bigint
LANGUAGE sql
IMMUTABLE
PARALLEL SAFE
AS $$
    SELECT 4279379::bigint
$$;

COMMENT ON FUNCTION os_ingest.release_lock_key() IS
    'Advisory lock key held (pg_advisory_xact_lock) by FULL promote and by the Upload COU apply.';

-- Transaction-level advisory lock key that serialises import_version, build_indexes and promote, so
-- two runs cannot recreate or index the shared FULL staging tables at the same time. Always taken
-- before release_lock_key() when both are needed.
-- 4279380 is the key above plus one.
CREATE FUNCTION os_ingest.import_lock_key()
RETURNS bigint
LANGUAGE sql
IMMUTABLE
PARALLEL SAFE
AS $$
    SELECT 4279380::bigint
$$;

COMMENT ON FUNCTION os_ingest.import_lock_key() IS
    'Advisory lock key held (pg_advisory_xact_lock) by FULL import_version, build_indexes and promote.';

-- import_version reads Landing with aws_s3. On Aurora the extensions are installed here when the
-- migration role is allowed to; elsewhere (local Postgres) they are skipped. The Aurora cluster
-- also needs an s3Import IAM role and KMS decrypt on the Landing bucket (see database/README.md).
DO $$
BEGIN
    IF EXISTS (SELECT 1 FROM pg_available_extensions WHERE name = 'aws_s3') THEN
        CREATE EXTENSION IF NOT EXISTS aws_s3 CASCADE;
    ELSE
        RAISE NOTICE 'aws_s3 extension is not available; import_version needs it to read Landing';
    END IF;
EXCEPTION WHEN insufficient_privilege THEN
    RAISE WARNING 'Not allowed to create aws_s3; ask a DBA to run CREATE EXTENSION aws_s3 CASCADE';
END
$$;
