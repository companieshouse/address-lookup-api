--liquibase formatted sql

--changeset address-lookup-api:"001 Create os_data Schema"
CREATE SCHEMA os_data;

--rollback DROP SCHEMA os_data;


--changeset address-lookup-api:"002 rename add_gb_builtaddress to add_gb_builtaddress_v3"
ALTER TABLE add_gb_builtaddress RENAME TO add_gb_builtaddress_v3;

--rollback ALTER TABLE add_gb_builtaddress_v3 RENAME TO add_gb_builtaddress SET SCHEMA public;


--changeset address-lookup-api:"003 rename add_isl_builtaddress to add_isl_builtaddress_v3"
ALTER TABLE add_isl_builtaddress RENAME TO add_isl_builtaddress_v3;

--rollback ALTER TABLE add_isl_builtaddress_v3 RENAME TO add_isl_builtaddress;


--changeset address-lookup-api:"004 rename add_gb_royalmailaddress to add_gb_royalmailaddress_v1"
ALTER TABLE add_gb_royalmailaddress RENAME TO add_gb_royalmailaddress_v1;

--rollback ALTER TABLE add_gb_royalmailaddress_v1 RENAME TO add_gb_royalmailaddress;


--changeset address-lookup-api:"005 rename add_isl_royalmailaddress to add_isl_royalmailaddress_v1"
ALTER TABLE add_isl_royalmailaddress RENAME TO add_isl_royalmailaddress_v1;

--rollback ALTER TABLE add_isl_royalmailaddress_v1 RENAME TO add_isl_royalmailaddress;


--changeset address-lookup-api:"006 create add-gb-builtaddress postcode index"
CREATE INDEX idx_builtaddress_postcode_normalized ON add_gb_builtaddress_v3 (upper(replace(postcode, ' ', '')));

--rollback DROP INDEX idx_builtaddress_postcode_normalized;


--changeset address-lookup-api:"007 create add-isl-builtaddress postcode index"
CREATE INDEX idx_isl_builtaddress_postcode_normalized ON add_isl_builtaddress_v3 (upper(replace(postcode, ' ', '')));

--rollback DROP INDEX idx_isl_builtaddress_postcode_normalized


--changeset address-lookup-api:"008 Move add_gb_builtaddress_v3 to os_data schema
ALTER TABLE add_gb_builtaddress_v3 SET SCHEMA os_data;

--rollback ALTER TABLE add_gb_builtaddress_v3 SET SCHEMA public;


--changeset address-lookup-api:"009 Move add_isl_builtaddress_v3 to os_data schema
ALTER TABLE add_isl_builtaddress_v3 SET SCHEMA os_data;

--rollback ALTER TABLE add_isl_builtaddress_v3 SET SCHEMA public;


--changeset address-lookup-api:"010 Move add_gb_royalmailaddress_v1 to os_data schema
ALTER TABLE add_gb_royalmailaddress_v1 SET SCHEMA os_data;

--rollback ALTER TABLE add_gb_royalmailaddress_v1 SET SCHEMA public;


--changeset address-lookup-api:"011 Move add_isl_royalmailaddress_v1 to os_data schema
ALTER TABLE add_isl_royalmailaddress_v1 SET SCHEMA os_data;

--rollback ALTER TABLE add_isl_royalmailaddress_v1 SET SCHEMA public;

