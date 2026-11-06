# Upload Lambda

Event-driven staging step. It only stages data and writes the import ledger; checking release
completeness and promotion belong to ingest.

## Trigger

The Upload SQS queue, fed by the Scan Lambda's clean-scan events:

| Event | Object | Action |
|-------|--------|--------|
| `ManifestReady` | `acquisitions/<target>/<releaseId>.json` in the ingest bucket | Insert one row per dataset into `os_ingest.data_package_manifest` (`ON CONFLICT DO NOTHING`) |
| `DatasetReady` | `Scanned/<releaseId>/<dataset>.csv` in the scanned bucket | Validate header, `COPY` into the landing table and insert the `os_ingest.import_ledger` row in one transaction |

Before reading any object the Lambda re-checks the `GuardDutyMalwareScanStatus` tag on the exact
object version. A CSV whose manifest has not been recorded yet is retried until it is.

Redelivery is harmless: manifests and ledger rows use `ON CONFLICT DO NOTHING`, and a dataset
already in the ledger is not loaded again. The ledger `row_count` is the number of rows loaded.

## Permanent failures

Failures that redelivery cannot fix (header or `schemaVersion` mismatch, row-count mismatch,
rows rejected by the database, invalid or conflicting manifest, scan not clean) publish a
`StepRejected` event (`step: "upload"`) to the ingest event bus and acknowledge the message, so
quarantine can block the release. If the event cannot be published the message is retried.
Malformed or inconsistent events (`INVALID_EVENT`) are rejected the same way when they carry a
usable release id, and logged and acknowledged when they do not. Unexpected exceptions from
libraries or infrastructure are never treated as invalid events: they are retried and end up in
the DLQ.

## Configuration

| Variable | Purpose |
|----------|---------|
| `SCANNED_BUCKET` | Bucket holding extracted CSVs |
| `INGEST_BUCKET` | Bucket holding manifests |
| `EVENT_BUS` | Event bus for `StepRejected` |
| `DB_HOST`, `DB_PORT`, `DB_NAME`, `DB_USER` | RDS Proxy endpoint, database and upload role |

The Lambda connects through RDS Proxy with IAM authentication (a fresh token per connection) over
TLS, as the upload role. Its execution role needs `rds-db:connect` for that user. The upload role
can only reach `os_ingest` and the `os_stage_*` schemas; it has no access to `os_data`,
`os_prev` or `os_control`, and cannot execute `os_ingest` routines (see migration
`V2026.006`). The role itself is created outside Flyway.

Session timeouts (`timezone`, `statement_timeout`, `lock_timeout`) are set on the role by
`V2026.006` rather than as connection startup parameters, which would pin RDS Proxy sessions.
The migration only applies the grants and settings if `upload_role` already exists (it logs a
warning otherwise), so create the role before running migrations.

## Deployment constraints

The pool holds one connection and a load can run for up to the 13 minute `statement_timeout`
while holding a transaction and advisory lock. The Lambda therefore needs:

- a timeout above 840 seconds (the JDBC `socketTimeout`);
- an SQS visibility timeout of at least six times the Lambda timeout;
- reserved concurrency sized to the RDS Proxy connection budget, with a DLQ and alarm on its depth.

S3 reads use the AWS SDK default socket read timeout (30 seconds), so a stalled download fails
and is retried rather than holding the lock.
