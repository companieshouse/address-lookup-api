--liquibase formatted sql

-- Jira: ALS-13
-- Description: create os control schema
-- Author: team-atlas
-- Created: 2026-10-12

--changeset team-atlas:2026-002:01
--comment: Create os_control Schema
CREATE SCHEMA os_control;

--rollback DROP SCHEMA os_control;


--changeset team-atlas:2026-002:02
--comment: Create schema_contract table
CREATE TABLE os_control.schema_contract (
  dataset text PRIMARY KEY,
  version text NOT NULL);

--rollback DROP TABLE os_control.schema_contract;


--changeset team-atlas:2026-002:03
--comment: Create run table
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


--changeset team-atlas:2026-002:04
--comment: Create release table
CREATE TABLE os_control.release (
 valid_from date PRIMARY KEY,
 digest text NOT NULL,
 mode text NOT NULL,
 manifest jsonb NOT NULL,
 run_id uuid NOT NULL REFERENCES os_control.run(id),
 completed_at timestamptz NOT NULL DEFAULT now()
);

--rollback DROP TABLE os_control.release;


--changeset team-atlas:2026-002:05
--comment: Create watermark table
CREATE TABLE os_control.watermark (
 singleton boolean PRIMARY KEY DEFAULT true CHECK (singleton),
 valid_from date NOT NULL REFERENCES os_control.release(valid_from)
);

--rollback DROP TABLE os_control.watermark;


--changeset team-atlas:2026-002:06
--comment: Populate schema_contract
INSERT INTO os_control.schema_contract VALUES
  ('add_gb_builtaddress','3.0'),
  ('add_isl_builtaddress','3.0'),
  ('add_gb_royalmailaddress','1.0'),
  ('add_isl_royalmailaddress','1.0');

--rollback DELETE FROM os_control.schema_contract WHERE dataset IN ('add_gb_builtaddress', 'add_isl_builtaddress', 'add_gb_royalmailaddress', 'add_isl_royalmailaddress');
