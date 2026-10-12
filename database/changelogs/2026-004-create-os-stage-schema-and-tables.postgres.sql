--liquibase formatted sql

-- Jira: ALS-13
-- Description: create os stage schema and tables
-- Author: team-atlas
-- Created: 2026-10-12

--changeset team-atlas:2026-004:01
--comment: Create os_stage Schema
CREATE SCHEMA os_stage;

--rollback DROP SCHEMA os_stage;


--changeset team-atlas:2026-004:02
--comment: Copy add_gb_builtaddress_v3 table to os_stage
CREATE TABLE os_stage.add_gb_builtaddress_v3 (LIKE os_data.add_gb_builtaddress_v3 INCLUDING ALL);
INSERT INTO os_stage.add_gb_builtaddress_v3
SELECT * FROM os_data.add_gb_builtaddress_v3;

--rollback DROP TABLE os_stage.add_gb_builtaddress_v3;

--changeset team-atlas:2026-004:03
--comment: Copy add_isl_builtaddress_v3 table to os_stage
CREATE TABLE os_stage.add_isl_builtaddress_v3 (LIKE os_data.add_isl_builtaddress_v3 INCLUDING ALL);
INSERT INTO os_stage.add_isl_builtaddress_v3
SELECT * FROM os_data.add_isl_builtaddress_v3;

--rollback DROP TABLE os_stage.add_isl_builtaddress_v3;

--changeset team-atlas:2026-004:04
--comment: Copy add_gb_royalmailaddress_v1 table to os_stage
CREATE TABLE os_stage.add_gb_royalmailaddress_v1 (LIKE os_data.add_gb_royalmailaddress_v1 INCLUDING ALL);
INSERT INTO os_stage.add_gb_royalmailaddress_v1
SELECT * FROM os_data.add_gb_royalmailaddress_v1;

--rollback DROP TABLE os_stage.add_gb_royalmailaddress_v1;

--changeset team-atlas:2026-004:05
--comment: Copy add_isl_royalmailaddress_v1 table to os_stage
CREATE TABLE os_stage.add_isl_royalmailaddress_v1 (LIKE os_data.add_isl_royalmailaddress_v1 INCLUDING ALL);
INSERT INTO os_stage.add_isl_royalmailaddress_v1
SELECT * FROM os_data.add_isl_royalmailaddress_v1;

--rollback DROP TABLE os_stage.add_isl_royalmailaddress_v1;
