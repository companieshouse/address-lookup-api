-- Jira: ALS-13
-- Description: create os data schema and tables
-- Author: team-atlas
-- Created: 2026-10-12
-- Source mapping: database/changelogs/2026-003-create-os-data-schema-and-tables.postgres.sql (2026-003:01-2026-003:11)

CREATE SCHEMA os_data;



ALTER TABLE add_gb_builtaddress RENAME TO add_gb_builtaddress_v3;



ALTER TABLE add_isl_builtaddress RENAME TO add_isl_builtaddress_v3;



ALTER TABLE add_gb_royalmailaddress RENAME TO add_gb_royalmailaddress_v1;



ALTER TABLE add_isl_royalmailaddress RENAME TO add_isl_royalmailaddress_v1;



CREATE INDEX idx_builtaddress_postcode_normalized ON add_gb_builtaddress_v3 (upper(replace(postcode, ' ', '')));



CREATE INDEX idx_isl_builtaddress_postcode_normalized ON add_isl_builtaddress_v3 (upper(replace(postcode, ' ', '')));



ALTER TABLE add_gb_builtaddress_v3 SET SCHEMA os_data;



ALTER TABLE add_isl_builtaddress_v3 SET SCHEMA os_data;



ALTER TABLE add_gb_royalmailaddress_v1 SET SCHEMA os_data;



ALTER TABLE add_isl_royalmailaddress_v1 SET SCHEMA os_data;
