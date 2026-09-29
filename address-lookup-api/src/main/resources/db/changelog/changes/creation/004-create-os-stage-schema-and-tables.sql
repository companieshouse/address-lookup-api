--liquibase formatted sql

--changeset address-lookup-api:"001 Create os_stage Schema"
CREATE SCHEMA os_stage;

--rollback DROP SCHEMA os_stage;


--changeset address-lookup-api:"002 Copy add_gb_builtaddress_v3 table to os_stage"
CREATE TABLE os_stage.add_gb_builtaddress_v3 (LIKE os_data.add_gb_builtaddress_v3 INCLUDING ALL);
INSERT INTO os_stage.add_gb_builtaddress_v3
SELECT * FROM os_data.add_gb_builtaddress_v3;

--rollback DROP TABLE os_stage.add_gb_builtaddress_v3;

--changeset address-lookup-api:"003 Copy add_isl_builtaddress_v3 table to os_stage"
CREATE TABLE os_stage.add_isl_builtaddress_v3 (LIKE os_data.add_isl_builtaddress_v3 INCLUDING ALL);
INSERT INTO os_stage.add_isl_builtaddress_v3
SELECT * FROM os_data.add_isl_builtaddress_v3;

--rollback DROP TABLE os_stage.add_isl_builtaddress_v3;

--changeset address-lookup-api:"004 Copy add_gb_royalmailaddress_v1 table to os_stage"
CREATE TABLE os_stage.add_gb_royalmailaddress_v1 (LIKE os_data.add_gb_royalmailaddress_v1 INCLUDING ALL);
INSERT INTO os_stage.add_gb_royalmailaddress_v1
SELECT * FROM os_data.add_gb_royalmailaddress_v1;

--rollback DROP TABLE os_stage.add_gb_royalmailaddress_v1;

--changeset address-lookup-api:"005 Copy add_isl_royalmailaddress_v1 table to os_stage"
CREATE TABLE os_stage.add_isl_royalmailaddress_v1 (LIKE os_data.add_isl_royalmailaddress_v1 INCLUDING ALL);
INSERT INTO os_stage.add_isl_royalmailaddress_v1
SELECT * FROM os_data.add_isl_royalmailaddress_v1;

--rollback DROP TABLE os_stage.add_isl_royalmailaddress_v1;
