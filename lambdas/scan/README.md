# Scan Lambda

Stateless router for GuardDuty Malware Protection scan results (EventBridge, `aws.guardduty`).
ZIP keys are parsed with the shared `KeyLayout` (`zips/<releaseId>/<dataset>.zip`).

| GuardDuty result | Published event |
|---|---|
| `NO_THREATS_FOUND` | `ExtractRequested` (releaseId, dataset, bucket, key, versionId) |
| `THREATS_FOUND` | `StepRejected{MALWARE_FOUND}` |
| `UNSUPPORTED`, `ACCESS_DENIED`, `FAILED`, unknown | `StepRejected{SCAN_INCONCLUSIVE}` |
| invalid input | `StepRejected{INVALID_INPUT}` |

Config: `INGEST_BUCKET`, `EVENT_BUS` (plus `SOURCE_BUCKET`, required by shared properties).
IAM: `events:PutEvents` on the bus only; no S3 access. Duplicate events are republished; a
PutEvents failure fails the invocation so retry/DLQ applies.
