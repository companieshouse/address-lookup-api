# Scan Lambda

Build target and Lambda runtime: Java 25.

Stateless router for GuardDuty Malware Protection scan results (EventBridge, `aws.guardduty`).
ZIPs at `landing/raw/<releaseId>/<dataset>.zip` and CSVs at
`landing/extracted/<releaseId>/<dataset>.csv` are accepted; the release ID is copied unchanged
into the published event. Set `INGEST_BUCKET` and `SCANNED_BUCKET` to the same acquisition
bucket. GuardDuty protection plans and EventBridge rules must include **only** these two
Landing prefixes, never the whole bucket.

Control holds cursors, acquisition manifests, receipts and checksum evidence; it is not scanned.
The router rejects Control and Quarantine keys even if a scan event is delivered by mistake.

| GuardDuty result | Published event |
|---|---|
| `NO_THREATS_FOUND` | ZIP: `ExtractRequested`; CSV: `DatasetReady` |
| `THREATS_FOUND` | `StepRejected{MALWARE_FOUND}` |
| `UNSUPPORTED`, `ACCESS_DENIED`, `FAILED`, unknown | `StepRejected{SCAN_INCONCLUSIVE}` |
| invalid input | `StepRejected{INVALID_INPUT}` |

Config: `INGEST_BUCKET`, `SCANNED_BUCKET` (same acquisition bucket), `EVENT_BUS`. Scan does not require
`SOURCE_BUCKET`.
IAM: `events:PutEvents` on the bus only; no S3 access. Duplicate events are republished; a
PutEvents failure fails the invocation so retry/DLQ applies.

## Output contract

Events are published to `EVENT_BUS` with source
`uk.gov.companieshouse.address-lookup.ingest`. The examples below show the source, detail type
and JSON detail delivered to consumers; EventBridge adds envelope fields such as `id`, `time`,
`account` and `region`. `schemaVersion` is the integer `1`, not a string.
Scan publishes events, not S3 receipts.

### Clean ZIP: ExtractRequested

Unzip receives the exact bucket, key and version ID reported by GuardDuty.

```json
{
  "source": "uk.gov.companieshouse.address-lookup.ingest",
  "detail-type": "ExtractRequested",
  "detail": {
    "schemaVersion": 1,
    "releaseId": "12345678-1234-1234-1234-123456789abc",
    "dataset": "add_gb_builtaddress",
    "object": {
      "bucket": "<ingest bucket>",
      "key": "landing/raw/12345678-1234-1234-1234-123456789abc/add_gb_builtaddress.zip",
      "versionId": "v1"
    }
  }
}
```

### Clean CSV: DatasetReady

Import receives the exact CSV version reported by GuardDuty, independently of the other datasets.

```json
{
  "source": "uk.gov.companieshouse.address-lookup.ingest",
  "detail-type": "DatasetReady",
  "detail": {
    "schemaVersion": 1,
    "releaseId": "20780-131061",
    "dataset": "add_gb_builtaddress",
    "object": {
      "bucket": "<clean CSV bucket>",
      "key": "landing/extracted/20780-131061/add_gb_builtaddress.csv",
      "versionId": "c3"
    }
  }
}
```

### Rejected scan: StepRejected

```json
{
  "source": "uk.gov.companieshouse.address-lookup.ingest",
  "detail-type": "StepRejected",
  "detail": {
    "schemaVersion": 1,
    "releaseId": "12345678-1234-1234-1234-123456789abc",
    "dataset": "add_gb_builtaddress",
    "object": {
      "bucket": "<ingest bucket>",
      "key": "landing/raw/12345678-1234-1234-1234-123456789abc/add_gb_builtaddress.zip",
      "versionId": "v1"
    },
    "step": "zip-scan",
    "code": "MALWARE_FOUND",
    "message": "Example.Malware",
    "objectUri": "s3://<ingest bucket>/landing/raw/12345678-1234-1234-1234-123456789abc/add_gb_builtaddress.zip"
  }
}
```

| Field | Values |
|---|---|
| `step` | `zip-scan`, `csv-scan`, `receipt-scan` or `manifest-scan`; `scan` when the key cannot be parsed |
| `code` | `MALWARE_FOUND`, `SCAN_INCONCLUSIVE` or `INVALID_INPUT` |
| `message` | Threat names (or `THREATS_FOUND` if names are unavailable), the inconclusive result (or `Missing scan result`), or the input-validation reason |
| `objectUri` | `s3://<bucket>/<key>` using the original bucket and key, included when both are available, even if the key is invalid |

For invalid input, `releaseId` and `dataset` are omitted if the key cannot be parsed.
The object contains the available bucket, key and version ID fields even if parsing fails;
missing fields are omitted, and `object` is omitted when none are available.
Scan routing logs ("Scan routed") include `event_id`, `release_id`, `dataset`, `file_type`,
`scan_result`, `version_id`, `decision` and `rejection_code` for CloudWatch correlation;
`objectUri` is not included in the log context.
No `ExtractRequested`, `DatasetReady`, `ReceiptReady` or `ManifestReady`
is published for a rejected input.
Duplicate deliveries can publish the same event again. A failed publication throws so the
invocation can be retried.
