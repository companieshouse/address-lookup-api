--liquibase formatted sql

--changeset address-lookup-api:"001 Create os_control Schema"
CREATE SCHEMA os_control;

--rollback DROP SCHEMA os_control;


--changeset address-lookup-api:"002 Create schema_contract table"
CREATE TABLE os_control.schema_contract (
  dataset text PRIMARY KEY, 
  version text NOT NULL);

--rollback DROP TABLE os_control.schema_contract;


--changeset address-lookup-api:"003 Create run table"
CREATE TABLE os_control.run (
 id uuid PRIMARY KEY, 
 started_at timestamptz NOT NULL DEFAULT now(),
 finished_at timestamptz, 
 release_date date,
 status text NOT NULL,
 detail text,
 CHECK (status IN ('RUNNING','COMPLETED','SKIPPED','FAILED'))
);

--rollback DROP TABLE os_control.run;


--changeset address-lookup-api:"004 Create release table"
CREATE TABLE os_control.release (
 valid_from date PRIMARY KEY, 
 digest text NOT NULL,
 mode text NOT NULL,
 manifest jsonb NOT NULL,
 run_id uuid NOT NULL REFERENCES os_control.run(id),
 completed_at timestamptz NOT NULL DEFAULT now()
);

--rollback DROP TABLE os_control.release;


--changeset address-lookup-api:"005 Create watermark table"
CREATE TABLE os_control.watermark (
 singleton boolean PRIMARY KEY DEFAULT true CHECK (singleton),
 valid_from date NOT NULL REFERENCES os_control.release(valid_from)
);

--rollback DROP TABLE os_control.watermark;


--changeset address-lookup-api:"006 Populate schema_contract"
INSERT INTO os_control.schema_contract VALUES
  ('add_gb_builtaddress','3.0'),
  ('add_isl_builtaddress','3.0'),
  ('add_gb_royalmailaddress','1.0'),
  ('add_isl_royalmailaddress','1.0');

--rollback DELETE FROM os_control.schema_contract WHERE dataset IN ('add_gb_builtaddress', 'add_isl_builtaddress', 'add_gb_royalmailaddress', 'add_isl_royalmailaddress');
