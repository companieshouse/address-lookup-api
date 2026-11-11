-- Jira: ALS-113
-- Purpose: Landing tables and data package manifest written by the upload Lambda, plus a unique
--          ledger row per (release, dataset) so a re-extracted CSV can never be counted twice.
-- Author/team: Address Lookup Service
-- Date: 2026-11-06
CREATE SCHEMA os_stage_raw;
CREATE SCHEMA os_stage_full;

-- release_id defaults from the os.release_id setting that AuroraOperations sets per transaction.
DO $$
DECLARE
    landing_schema text;
    dataset text;
BEGIN
    FOREACH landing_schema IN ARRAY ARRAY['os_stage_raw', 'os_stage_full'] LOOP
        FOREACH dataset IN ARRAY ARRAY['add_gb_royalmailaddress', 'add_isl_royalmailaddress'] LOOP
            EXECUTE format('CREATE TABLE %I.%I (LIKE os_data.%I)',
                           landing_schema, dataset, dataset || '_v2');
            -- The live tables store these as date, which would silently drop the catalogue's time.
            EXECUTE format('ALTER TABLE %I.%I '
                           || 'ALTER COLUMN versiondate TYPE timestamp(0) without time zone, '
                           || 'ALTER COLUMN updatedate TYPE timestamp(0) without time zone, '
                           || 'ALTER COLUMN entrydate TYPE timestamp(0) without time zone, '
                           || 'ALTER COLUMN unmatchedreasondate TYPE timestamp(0) without time zone, '
                           || 'ALTER COLUMN matchdate TYPE timestamp(0) without time zone',
                           landing_schema, dataset);
            -- The catalogue permits 50 characters; the live tables only hold 25.
            EXECUTE format('ALTER TABLE %I.%I ALTER COLUMN description TYPE varchar(50)',
                           landing_schema, dataset);
            EXECUTE format('ALTER TABLE %I.%I ALTER COLUMN release_id TYPE uuid USING release_id::uuid',
                           landing_schema, dataset);
            EXECUTE format('ALTER TABLE %I.%I ALTER COLUMN release_id '
                           || 'SET DEFAULT current_setting(''os.release_id'')::uuid',
                           landing_schema, dataset);
            EXECUTE format('CREATE INDEX %I ON %I.%I (release_id)',
                           dataset || '_release_id_idx', landing_schema, dataset);
        END LOOP;
    END LOOP;
END
$$;

ALTER TABLE os_ingest.import_ledger
    ADD CONSTRAINT import_ledger_release_dataset_key UNIQUE (release_id, dataset);

CREATE TABLE os_ingest.data_package_manifest (
    release_id uuid NOT NULL,
    dataset text NOT NULL,
    supply_type text NOT NULL CHECK (supply_type IN ('FULL', 'COU')),
    previous_release date,
    target_release date NOT NULL,
    data_package_id text NOT NULL,
    version_id text NOT NULL,
    schema_version text NOT NULL,
    record_count bigint NOT NULL CHECK (record_count >= 0),
    recorded_at timestamptz NOT NULL DEFAULT now(),
    CONSTRAINT data_package_manifest_pkey PRIMARY KEY (release_id, dataset)
);

COMMENT ON TABLE os_ingest.data_package_manifest IS
    'One row per dataset the release manifest promises; ingest requires ledger rows to match record_count.';

-- Routines are executable by PUBLIC by default, which would let the upload role call any
-- os_ingest routine (such as promote_full) it has schema USAGE for. Routines created from now on
-- must be granted explicitly.
REVOKE EXECUTE ON ALL ROUTINES IN SCHEMA os_ingest FROM PUBLIC;
ALTER DEFAULT PRIVILEGES REVOKE EXECUTE ON ROUTINES FROM PUBLIC;

-- The upload role is created outside Flyway; it is the only role crossing the trust wall and
-- has no access to os_data, os_prev or os_control.
DO $$
BEGIN
    IF EXISTS (SELECT 1 FROM pg_roles WHERE rolname = 'upload_role') THEN
        REVOKE ALL ON SCHEMA os_data, os_prev, os_control FROM upload_role;
        REVOKE ALL ON ALL TABLES IN SCHEMA os_data, os_prev, os_control FROM upload_role;
        GRANT USAGE ON SCHEMA os_ingest, os_stage_raw, os_stage_full TO upload_role;
        GRANT SELECT, INSERT ON os_ingest.import_ledger, os_ingest.data_package_manifest
            TO upload_role;
        GRANT SELECT, INSERT, DELETE ON ALL TABLES IN SCHEMA os_stage_raw, os_stage_full
            TO upload_role;
    ELSE
        RAISE WARNING 'upload_role does not exist: upload grants were NOT applied; '
                      'create the role and re-run the grants before deploying the upload Lambda';
    END IF;
END
$$;
