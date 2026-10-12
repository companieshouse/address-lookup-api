-- Jira: ALS-13
-- Description: create os stage schema and tables
-- Author: team-atlas
-- Created: 2026-10-12
-- Source mapping: database/changelogs/2026-004-create-os-stage-schema-and-tables.postgres.sql (2026-004:01-2026-004:05)

CREATE SCHEMA os_stage;



CREATE TABLE os_stage.add_gb_builtaddress_v3 (LIKE os_data.add_gb_builtaddress_v3 INCLUDING ALL);
INSERT INTO os_stage.add_gb_builtaddress_v3
SELECT * FROM os_data.add_gb_builtaddress_v3;


CREATE TABLE os_stage.add_isl_builtaddress_v3 (LIKE os_data.add_isl_builtaddress_v3 INCLUDING ALL);
INSERT INTO os_stage.add_isl_builtaddress_v3
SELECT * FROM os_data.add_isl_builtaddress_v3;


CREATE TABLE os_stage.add_gb_royalmailaddress_v1 (LIKE os_data.add_gb_royalmailaddress_v1 INCLUDING ALL);
INSERT INTO os_stage.add_gb_royalmailaddress_v1
SELECT * FROM os_data.add_gb_royalmailaddress_v1;


CREATE TABLE os_stage.add_isl_royalmailaddress_v1 (LIKE os_data.add_isl_royalmailaddress_v1 INCLUDING ALL);
INSERT INTO os_stage.add_isl_royalmailaddress_v1
SELECT * FROM os_data.add_isl_royalmailaddress_v1;
