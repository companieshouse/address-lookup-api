# Scan Lambda

Stateless router for GuardDuty Malware Protection scan results (EventBridge, `aws.guardduty`).
The shared `KeyLayout` accepts ZIPs at `zips/<releaseId>/<dataset>.zip` in the ingest bucket and
CSV files at `csv/<releaseId>/<dataset>.csv` in the clean/scanned bucket.

| GuardDuty result | Published event |
|---|---|
| `NO_THREATS_FOUND` | ZIP: `ExtractRequested`; CSV: `DatasetReady` (releaseId, dataset, object bucket/key/versionId) |
| `THREATS_FOUND` | `StepRejected{MALWARE_FOUND}` |
| `UNSUPPORTED`, `ACCESS_DENIED`, `FAILED`, unknown | `StepRejected{SCAN_INCONCLUSIVE}` |
| invalid input | `StepRejected{INVALID_INPUT}` |

Config: `INGEST_BUCKET`, `SCANNED_BUCKET` (clean CSV bucket), `EVENT_BUS`. Scan does not require
`SOURCE_BUCKET`.
IAM: `events:PutEvents` on the bus only; no S3 access. Duplicate events are republished; a
PutEvents failure fails the invocation so retry/DLQ applies.
