-- Jira: ALS-143
-- Purpose: Add the completeness check shared by the FULL and COU workloads.
-- Author/team: Address Lookup Service
-- Date: 2026-11-17
-- Compares the manifest files and row counts with the import ledger for one release.
CREATE FUNCTION os_ingest.check_release_completeness(p_release_id text)
RETURNS TABLE (dataset text, file text, expected_rows bigint, loaded_rows bigint, status text)
LANGUAGE sql
STABLE
AS $$
    WITH expected AS (
        SELECT e.dataset, e.file, e.rows
        FROM os_stage.data_package_manifest m,
            LATERAL jsonb_to_recordset(m.datasets)
                AS e(dataset text, file text, rows bigint)
        WHERE m.release_id = p_release_id
    )
    SELECT
        COALESCE(e.dataset, l.dataset),
        COALESCE(e.file, l.file),
        e.rows,
        l.rows,
        CASE
            WHEN e.file IS NULL THEN 'UNEXPECTED'
            WHEN l.file IS NULL THEN 'MISSING'
            WHEN e.rows IS DISTINCT FROM l.rows THEN 'ROW_COUNT_MISMATCH'
            ELSE 'OK'
        END
    FROM expected e
    FULL JOIN os_stage.import_ledger l
        ON l.release_id = p_release_id AND l.dataset = e.dataset AND l.file = e.file
    WHERE l.release_id = p_release_id OR l.release_id IS NULL
$$;

-- True only when the manifest exists and every file is loaded with the expected rows.
CREATE FUNCTION os_ingest.is_release_complete(p_release_id text)
RETURNS boolean
LANGUAGE sql
STABLE
AS $$
    SELECT EXISTS (SELECT 1 FROM os_stage.data_package_manifest WHERE release_id = p_release_id)
        AND NOT EXISTS (
            SELECT 1 FROM os_ingest.check_release_completeness(p_release_id) c
            WHERE c.status <> 'OK'
        )
$$;
