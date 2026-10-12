-- Jira: ALS-13
-- Description: create os control schema
-- Author: team-atlas
-- Created: 2026-10-12
-- Source mapping: database/changelogs/2026-002-create-os-control-schema.postgres.sql (2026-002:01-2026-002:06)

CREATE SCHEMA os_control;



CREATE TABLE os_control.schema_contract (
  dataset text PRIMARY KEY,
  version text NOT NULL);



CREATE TABLE os_control.run (
 id uuid PRIMARY KEY,
 started_at timestamptz NOT NULL DEFAULT now(),
 finished_at timestamptz,
 release_date date,
 status text NOT NULL,
 detail text,
 CHECK (status IN ('RUNNING','COMPLETED','SKIPPED','FAILED'))
);



CREATE TABLE os_control.release (
 valid_from date PRIMARY KEY,
 digest text NOT NULL,
 mode text NOT NULL,
 manifest jsonb NOT NULL,
 run_id uuid NOT NULL REFERENCES os_control.run(id),
 completed_at timestamptz NOT NULL DEFAULT now()
);



CREATE TABLE os_control.watermark (
 singleton boolean PRIMARY KEY DEFAULT true CHECK (singleton),
 valid_from date NOT NULL REFERENCES os_control.release(valid_from)
);



INSERT INTO os_control.schema_contract VALUES
  ('add_gb_builtaddress','3.0'),
  ('add_isl_builtaddress','3.0'),
  ('add_gb_royalmailaddress','1.0'),
  ('add_isl_royalmailaddress','1.0');
