--liquibase formatted sql

-- Jira: ALS-13
-- Description: create os data schema and tables
-- Author: team-atlas
-- Created: 2026-10-12

--changeset team-atlas:2026-003:01
--comment: Create os_data Schema
CREATE SCHEMA os_data;

--rollback DROP SCHEMA os_data;


--changeset team-atlas:2026-003:02
--comment: rename add_gb_builtaddress to add_gb_builtaddress_v3
ALTER TABLE add_gb_builtaddress RENAME TO add_gb_builtaddress_v3;

--rollback ALTER TABLE add_gb_builtaddress_v3 RENAME TO add_gb_builtaddress;


--changeset team-atlas:2026-003:03
--comment: rename add_isl_builtaddress to add_isl_builtaddress_v3
ALTER TABLE add_isl_builtaddress RENAME TO add_isl_builtaddress_v3;

--rollback ALTER TABLE add_isl_builtaddress_v3 RENAME TO add_isl_builtaddress;


--changeset team-atlas:2026-003:04
--comment: rename add_gb_royalmailaddress to add_gb_royalmailaddress_v1
ALTER TABLE add_gb_royalmailaddress RENAME TO add_gb_royalmailaddress_v1;

--rollback ALTER TABLE add_gb_royalmailaddress_v1 RENAME TO add_gb_royalmailaddress;


--changeset team-atlas:2026-003:05
--comment: rename add_isl_royalmailaddress to add_isl_royalmailaddress_v1
ALTER TABLE add_isl_royalmailaddress RENAME TO add_isl_royalmailaddress_v1;

--rollback ALTER TABLE add_isl_royalmailaddress_v1 RENAME TO add_isl_royalmailaddress;


--changeset team-atlas:2026-003:06
--comment: create add-gb-builtaddress postcode index
CREATE INDEX idx_builtaddress_postcode_normalized ON add_gb_builtaddress_v3 (upper(replace(postcode, ' ', '')));

--rollback DROP INDEX idx_builtaddress_postcode_normalized;


--changeset team-atlas:2026-003:07
--comment: create add-isl-builtaddress postcode index
CREATE INDEX idx_isl_builtaddress_postcode_normalized ON add_isl_builtaddress_v3 (upper(replace(postcode, ' ', '')));

--rollback DROP INDEX idx_isl_builtaddress_postcode_normalized;


--changeset team-atlas:2026-003:08
--comment: Move add_gb_builtaddress_v3 to os_data schema
ALTER TABLE add_gb_builtaddress_v3 SET SCHEMA os_data;

--rollback ALTER TABLE os_data.add_gb_builtaddress_v3 SET SCHEMA public;


--changeset team-atlas:2026-003:09
--comment: Move add_isl_builtaddress_v3 to os_data schema
ALTER TABLE add_isl_builtaddress_v3 SET SCHEMA os_data;

--rollback ALTER TABLE os_data.add_isl_builtaddress_v3 SET SCHEMA public;


--changeset team-atlas:2026-003:10
--comment: Move add_gb_royalmailaddress_v1 to os_data schema
ALTER TABLE add_gb_royalmailaddress_v1 SET SCHEMA os_data;

--rollback ALTER TABLE os_data.add_gb_royalmailaddress_v1 SET SCHEMA public;


--changeset team-atlas:2026-003:11
--comment: Move add_isl_royalmailaddress_v1 to os_data schema
ALTER TABLE add_isl_royalmailaddress_v1 SET SCHEMA os_data;

--rollback ALTER TABLE os_data.add_isl_royalmailaddress_v1 SET SCHEMA public;
