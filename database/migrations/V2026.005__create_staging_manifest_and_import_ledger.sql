-- Jira: ALS-143
-- Purpose: Create the staging manifest and import ledger.
-- Author/team: Address Lookup Service
-- Date: 2026-11-17
CREATE TABLE os_stage.data_package_manifest (
    release_id text NOT NULL,
    mode text NOT NULL CHECK (mode IN ('FULL', 'COU')),
    target text NOT NULL,
    previous text,
    package_id text NOT NULL,
    version_id text NOT NULL,
    -- Array of {"dataset": text, "file": text, "rows": bigint}; shape is provisional.
    datasets jsonb NOT NULL,
    created_at timestamptz NOT NULL DEFAULT now(),
    CONSTRAINT data_package_manifest_pkey PRIMARY KEY (release_id),
    CONSTRAINT data_package_manifest_datasets_array CHECK (jsonb_typeof(datasets) = 'array')
);

CREATE TABLE os_stage.import_ledger (
    release_id text NOT NULL,
    dataset text NOT NULL,
    file text NOT NULL,
    rows bigint NOT NULL CHECK (rows >= 0),
    loaded_at timestamptz NOT NULL DEFAULT now(),
    CONSTRAINT import_ledger_release_dataset_file_key UNIQUE (release_id, dataset, file),
    CONSTRAINT import_ledger_release_fkey
        FOREIGN KEY (release_id) REFERENCES os_stage.data_package_manifest (release_id),
    CONSTRAINT import_ledger_dataset_fkey
        FOREIGN KEY (dataset) REFERENCES os_control.schema_contract (dataset)
);
