-- Jira: ALS-143
-- Purpose: Create the NGD staging tables the CSVs are loaded into.
-- Author/team: Address Lookup Service
-- Date: 2026-11-17
-- Staging tables mirror the active column list so COPY from the CSV lands unchanged.
-- Constraints and indexes are not copied: staging is a raw landing area. The geometry
-- column is derived from easting/northing and release_id is set from the manifest, so
-- both are dropped and re-added as nullable.
CREATE TABLE os_stage.add_gb_royalmailaddress (
    LIKE os_data.add_gb_royalmailaddress_v2 EXCLUDING ALL
);
CREATE TABLE os_stage.add_isl_royalmailaddress (
    LIKE os_data.add_isl_royalmailaddress_v2 EXCLUDING ALL
);

ALTER TABLE os_stage.add_gb_royalmailaddress
    DROP COLUMN geometry,
    DROP COLUMN release_id;
ALTER TABLE os_stage.add_isl_royalmailaddress
    DROP COLUMN geometry,
    DROP COLUMN release_id;

ALTER TABLE os_stage.add_gb_royalmailaddress ADD COLUMN release_id text;
ALTER TABLE os_stage.add_isl_royalmailaddress ADD COLUMN release_id text;
ALTER TABLE os_stage.add_gb_royalmailaddress ALTER COLUMN udprn DROP NOT NULL;
ALTER TABLE os_stage.add_isl_royalmailaddress ALTER COLUMN udprn DROP NOT NULL;

CREATE INDEX add_gb_royalmailaddress_release_idx ON os_stage.add_gb_royalmailaddress (release_id);
CREATE INDEX add_isl_royalmailaddress_release_idx ON os_stage.add_isl_royalmailaddress (release_id);
