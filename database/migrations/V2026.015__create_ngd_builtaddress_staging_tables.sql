-- Jira: ALS-143
-- Purpose: Create the staging tables for the built address and pre-build address datasets.
-- Author/team: Address Lookup Service
-- Date: 2026-11-20
-- Same approach as V2026.006 (os_stage, daily Upload) and V2026.010 (os_stage_full, FULL): the
-- os_stage tables mirror the CSV, so geometry is kept in CSV order (nullable) and release_id is
-- re-added nullable at the end; the os_stage_full tables have the active shape without constraints or indexes.
CREATE TABLE os_stage.add_gb_builtaddress (
    LIKE os_data.add_gb_builtaddress EXCLUDING ALL
);
CREATE TABLE os_stage.add_gb_prebuildaddress (
    LIKE os_data.add_gb_prebuildaddress EXCLUDING ALL
);
CREATE TABLE os_stage.add_isl_builtaddress (
    LIKE os_data.add_isl_builtaddress EXCLUDING ALL
);
CREATE TABLE os_stage.add_isl_prebuildaddress (
    LIKE os_data.add_isl_prebuildaddress EXCLUDING ALL
);

ALTER TABLE os_stage.add_gb_builtaddress DROP COLUMN release_id;
ALTER TABLE os_stage.add_gb_prebuildaddress DROP COLUMN release_id;
ALTER TABLE os_stage.add_isl_builtaddress DROP COLUMN release_id;
ALTER TABLE os_stage.add_isl_prebuildaddress DROP COLUMN release_id;

ALTER TABLE os_stage.add_gb_builtaddress ADD COLUMN release_id text;
ALTER TABLE os_stage.add_gb_prebuildaddress ADD COLUMN release_id text;
ALTER TABLE os_stage.add_isl_builtaddress ADD COLUMN release_id text;
ALTER TABLE os_stage.add_isl_prebuildaddress ADD COLUMN release_id text;

ALTER TABLE os_stage.add_gb_builtaddress ALTER COLUMN uprn DROP NOT NULL;
ALTER TABLE os_stage.add_gb_builtaddress ALTER COLUMN geometry DROP NOT NULL;
ALTER TABLE os_stage.add_gb_prebuildaddress ALTER COLUMN uprn DROP NOT NULL;
ALTER TABLE os_stage.add_gb_prebuildaddress ALTER COLUMN geometry DROP NOT NULL;
ALTER TABLE os_stage.add_isl_builtaddress ALTER COLUMN uprn DROP NOT NULL;
ALTER TABLE os_stage.add_isl_builtaddress ALTER COLUMN geometry DROP NOT NULL;
ALTER TABLE os_stage.add_isl_prebuildaddress ALTER COLUMN uprn DROP NOT NULL;
ALTER TABLE os_stage.add_isl_prebuildaddress ALTER COLUMN geometry DROP NOT NULL;

CREATE INDEX add_gb_builtaddress_release_idx ON os_stage.add_gb_builtaddress (release_id);
CREATE INDEX add_gb_prebuildaddress_release_idx ON os_stage.add_gb_prebuildaddress (release_id);
CREATE INDEX add_isl_builtaddress_release_idx ON os_stage.add_isl_builtaddress (release_id);
CREATE INDEX add_isl_prebuildaddress_release_idx ON os_stage.add_isl_prebuildaddress (release_id);

CREATE TABLE os_stage_full.add_gb_builtaddress (
    LIKE os_data.add_gb_builtaddress EXCLUDING ALL
);
CREATE TABLE os_stage_full.add_gb_prebuildaddress (
    LIKE os_data.add_gb_prebuildaddress EXCLUDING ALL
);
CREATE TABLE os_stage_full.add_isl_builtaddress (
    LIKE os_data.add_isl_builtaddress EXCLUDING ALL
);
CREATE TABLE os_stage_full.add_isl_prebuildaddress (
    LIKE os_data.add_isl_prebuildaddress EXCLUDING ALL
);

DO $$
BEGIN
    IF NOT EXISTS (SELECT 1 FROM pg_roles WHERE rolname = 'als_upload') THEN
        RAISE NOTICE 'Role als_upload does not exist; skipping Upload Lambda grants';
        RETURN;
    END IF;
    GRANT INSERT, SELECT ON os_stage.add_gb_builtaddress, os_stage.add_gb_prebuildaddress,
        os_stage.add_isl_builtaddress, os_stage.add_isl_prebuildaddress TO als_upload;
END
$$;
