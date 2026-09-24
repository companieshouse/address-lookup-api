# Scan Lambda operations

The scan Lambda is invoked by EventBridge for `aws.guardduty` findings. Its rule must include
GuardDuty object scan completion findings and grant the rule permission to invoke the Lambda.
The Lambda role needs `s3:GetObject`, `s3:PutObject` and `s3:ListBucket` scoped to
`Scanned/*` in `SCANNED_BUCKET`, `s3:GetObject` and `s3:ListBucket` scoped to
`acquisitions/*` in `SOURCE_BUCKET`, and `events:PutEvents` to `EVENT_BUS`.

It stores mutable state at `scan-state/<releaseId>/<dataset>/<ZIP|CSV>.json`. A clean ZIP emits
`UNZIP`; a clean CSV emits `IMPORT_READY`; an infected object emits `INFECTION_DETECTED`. Event
payloads contain only release and dataset identifiers, never bucket names or presigned URLs.

To recover an interrupted, duplicate, or out-of-order delivery, invoke the Lambda with:

```json
{"detail":{"action":"reconcile","releaseId":"12345678-1234-1234-1234-123456789abc"}}
```

Reconciliation re-publishes any clean command whose durable state has not recorded publication.
For a state gap, it first recovers the durable `GuardDutyMalwareScanStatus` object tag. Objects
present under `Scanned/<releaseId>/` with neither state nor a terminal GuardDuty tag are recorded
as `SCAN_MISSING`; they require a new GuardDuty scan or operator review before processing. ZIP and
CSV objects are reconciled separately, so no dataset waits for the other three datasets.
