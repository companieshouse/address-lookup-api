-- Jira: ALS-113
-- Purpose: Record each CSV loaded by the upload Lambda so reruns are idempotent and ingest can
--          check the loaded row counts against the release manifest.
-- Author/team: Address Lookup Service
-- Date: 2026-11-05
CREATE TABLE os_ingest.import_ledger (
    release_id uuid NOT NULL,
    dataset text NOT NULL,
    file text NOT NULL,
    row_count bigint NOT NULL CHECK (row_count >= 0),
    loaded_at timestamptz NOT NULL DEFAULT now(),
    CONSTRAINT import_ledger_pkey PRIMARY KEY (release_id, dataset, file)
);
