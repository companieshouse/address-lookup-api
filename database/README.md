# Database

Flyway SQL lives in `migrations/`. Local-only seed data lives in `local/` and is never packaged for
deployed environments. See the root `README.md` for how to build, run and test.

## Bootstrap rule

A release is either `FULL` (a complete copy of the dataset) or `COU` (a change-only update that
must be applied on top of the release named in its `previous` field).

`os_data.release_history` records every release applied to the active tables. When it has **no
rows**, the database holds no baseline, so a `COU` has nothing to build on. In that state only a
`FULL` release is accepted. Once a `FULL` has been applied, later `COU` releases are accepted in
order.

The rule is documented here and in `V2026.007`; it is not enforced by a database constraint. The
release workflows must check it using `os_data.release_history`.

## Datasets

`os_control.schema_contract` registers six datasets, each with its `schemaVersion` and the name of its
active table in `os_data` (`active_table`). The FULL procedures resolve tables from it and process only
datasets with `enabled = true`. The four schema 3.0 datasets are registered with `enabled = false`
because Discovery and the release manifest model support only the Royal Mail datasets; enable them
once that support is deployed.

| Dataset | Schema | Active table | SRID |
|---------|--------|--------------|------|
| `add_gb_royalmailaddress`, `add_isl_royalmailaddress` | 2.0 | `<dataset>_v2` | 27700 / 4258 |
| `add_gb_builtaddress`, `add_gb_prebuildaddress` | 3.0 | `<dataset>` | 27700 (easting/northing NOT NULL) |
| `add_isl_builtaddress`, `add_isl_prebuildaddress` | 3.0 | `<dataset>` | 4258 (easting/northing nullable) |

The schema 3.0 tables follow the OS DDL (`OrdnanceSurvey/osngd-resources`) column for column plus
`release_id`, with primary key `uprn`. Their `_altadd`, `_rltenty` and `_otrclass` sub-tables are
extract-only and are not created. Each has a `os_stage` and `os_stage_full` staging table, a primary key
and a postcode index in `os_control.index_definition` (the index list is to be agreed in review), and
`als_upload` INSERT/SELECT on its `os_stage` table. They are not in the `address_lookup` view and have no
local seed data.

## FULL load procedures

A FULL is imported by PostgreSQL directly from Landing (not by a Lambda), using procedures that
Flyway installs but never runs. Each call is one transaction and can be re-run after a failure.

| Step | Call | Effect |
|------|------|--------|
| 1 | `CALL os_ingest.import_version(data_package, version_id, bucket, region [, manifest_key [, csv_prefix]])` | Reads the manifest (default `full/<data_package>/<version_id>/manifest.json`), checks its `schemaVersion` and `crs` against `os_control.schema_contract` and the active geometry SRID, recreates the `os_stage_full.<table>` tables, loads `<csv_prefix><dataset>.csv` with `aws_s3.table_import_from_s3` (header must match the table), writes the manifest and ledger rows, then fails unless the completeness check passes. |
| 2 | `CALL os_ingest.build_indexes(release_id)` | Builds the `os_control.index_definition` indexes and keys on the FULL staging tables (`maintenance_work_mem` 1GB), then `ANALYZE`. |
| 3 | `CALL os_ingest.promote(release_id)` | Locks the lookup view and its tables, moves the active tables to `os_prev` (one previous generation kept for rollback), moves the staging tables into `os_data`, re-points `address_lookup.address_lookup` and inserts a `FULL` row (no `previous`) in `release_history`. It refuses empty tables and a release whose `target` is older than the latest `release_history` row. |

Pass `manifest_key` and `csv_prefix` when the Supply Pipeline wrote different keys (for example
`acquisitions/<target>/<releaseId>.json` and `Scanned/<releaseId>/`). The Supply Pipeline must check
that the manifest and every CSV are tagged `NO_THREATS_FOUND` before step 1, because `aws_s3` cannot
read object tags.

### Prerequisites and operating notes

- **Aurora only:** `aws_s3` and `aws_commons` must exist (V2026.010 only tries to create them and
  logs a warning when the migration role may not; the schema owner is deliberately not in
  `rds_superuser`, so create them as the master user, as `db-bootstrap` does for `postgis`, and grant
  the loader role USAGE on schema `aws_s3` and EXECUTE on its functions), the cluster needs an
  `s3Import` IAM role with read on the Landing bucket, and that role needs `kms:Decrypt` on the
  bucket key. None of this is in the Terraform in this repository yet.
- **Run as a role that is a member of the table owner role** (for example the schema-owner role).
  `promote` gives the staging tables the active tables' owner and privileges (including grant options)
  and fails if it cannot, so the API's view keeps working.
- **Direct connection, not RDS Proxy.** Step 1 can run for hours in one transaction: disable
  `statement_timeout` and `idle_in_transaction_session_timeout` for the session, and in `psql` keep
  autocommit on (with `AUTOCOMMIT off`, forgetting `COMMIT` after `promote` leaves the API blocked).
- **Locks.** All three steps take `os_ingest.import_lock_key()`, so they cannot overlap; `promote` then
  takes `os_ingest.release_lock_key()`. The Upload COU apply must take
  `pg_advisory_xact_lock(os_ingest.release_lock_key())` so it cannot overlap `promote`. Locks are
  requested with `lock_timeout` (60s for `import_version` and `build_indexes`, 3s for `promote`), so a
  blocked call fails instead of waiting for ever.
- **Reads during `promote`.** It takes `ACCESS EXCLUSIVE` on the view and tables for the duration of
  the swap (seconds). While it waits for a table lock, new reads queue behind it, so the wait is capped
  at 3s: a long-running query already reading the tables makes `promote` fail on `lock_timeout`.
  Wait for that query and re-run; do not retry in a tight loop.
- **No `db-migrate` during a FULL import.** `import_version` holds a read lock on the active tables
  for the whole load, so DDL on them would queue and block every API read behind it.
- **Releases older than the latest `release_history` target are rejected** by `import_version` and
  `promote`.
- **Rollback is one generation.** `os_prev` holds the tables replaced by the latest `promote`; the next
  `promote` drops it. After a successful `promote`, run `VACUUM (ANALYZE)` on the new `os_data` tables.
