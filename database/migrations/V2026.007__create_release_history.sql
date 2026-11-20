-- Jira: ALS-143
-- Purpose: Record every release applied to the active NGD tables.
-- Author/team: Address Lookup Service
-- Date: 2026-11-17
-- An empty release_history means bootstrap: only a FULL release may be accepted.
CREATE TABLE os_data.release_history (
    release_id text NOT NULL,
    previous text,
    mode text NOT NULL CHECK (mode IN ('FULL', 'COU')),
    target text NOT NULL,
    applied_at timestamptz NOT NULL DEFAULT now(),
    -- Per-dataset row counts, e.g. {"add_gb_royalmailaddress": 123}.
    row_counts jsonb NOT NULL DEFAULT '{}'::jsonb,
    CONSTRAINT release_history_pkey PRIMARY KEY (release_id),
    CONSTRAINT release_history_previous_fkey
        FOREIGN KEY (previous) REFERENCES os_data.release_history (release_id),
    CONSTRAINT release_history_row_counts_object CHECK (jsonb_typeof(row_counts) = 'object')
);
