# Scan Lambda

Build target and Lambda runtime: Java 21.

Stateless router for GuardDuty Malware Protection scan results (EventBridge, `aws.guardduty`).
Download ZIPs at `Scanned/<UUID releaseId>/<dataset>.zip` are accepted; the release ID is
copied unchanged into the published event. EventBridge rules must include the `Scanned/` prefix.
The shared `KeyLayout` accepts ZIPs at `zips/<releaseId>/<dataset>.zip` in the ingest bucket and
CSV files at `csv/<releaseId>/<dataset>.csv` in the clean/scanned bucket.
Download receipts at `Scanned/<UUID releaseId>/<dataset>.receipt.json` are accepted in
`INGEST_BUCKET`. Scan routes their scan results without reading the receipt contents.
Acquisition manifests at `acquisitions/<YYYY-MM-DD>/<UUID releaseId>.json` are also accepted in
`INGEST_BUCKET`. The date must be valid. Scan derives the release ID from the key, does not read
the manifest and does not compare the path date with the manifest's target date.
Include the `acquisitions/` prefix in the GuardDuty-to-Scan EventBridge rule.

| GuardDuty result | Published event |
|---|---|
| `NO_THREATS_FOUND` | ZIP: `ExtractRequested`; CSV: `DatasetReady`; receipt: `ReceiptReady`; manifest: `ManifestReady` |
| `THREATS_FOUND` | `StepRejected{MALWARE_FOUND}` |
| `UNSUPPORTED`, `ACCESS_DENIED`, `FAILED`, unknown | `StepRejected{SCAN_INCONCLUSIVE}` |
| invalid input | `StepRejected{INVALID_INPUT}` |

Config: `INGEST_BUCKET`, `SCANNED_BUCKET` (clean CSV bucket), `EVENT_BUS`. Scan does not require
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
      "key": "Scanned/12345678-1234-1234-1234-123456789abc/add_gb_builtaddress.zip",
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
      "key": "csv/20780-131061/add_gb_builtaddress.csv",
      "versionId": "c3"
    }
  }
}
```

### Clean receipt: ReceiptReady

Receipt notifications are separate from ZIP extraction and CSV import. Configure a separate
EventBridge consumer for `ReceiptReady` if needed; do not route it to CSV Import or Unzip.

```json
{
  "source": "uk.gov.companieshouse.address-lookup.ingest",
  "detail-type": "ReceiptReady",
  "detail": {
    "schemaVersion": 1,
    "releaseId": "f3d8c9a7-7b4e-4f62-a9d1-8c5e72f1b6a4",
    "dataset": "add_gb_royalmailaddress",
    "object": {
      "bucket": "<ingest bucket>",
      "key": "Scanned/f3d8c9a7-7b4e-4f62-a9d1-8c5e72f1b6a4/add_gb_royalmailaddress.receipt.json",
      "versionId": "r1"
    }
  }
}
```

### Clean acquisition manifest: ManifestReady

Manifest notifications are release-level events and have no `dataset` field. Configure a
separate EventBridge rule/consumer for `ManifestReady`; it must not trigger Unzip or CSV Import.
The exact object version comes from GuardDuty. Manifest contents, download URLs and checksums
are not included in the notification or read by Scan.

```json
{
  "source": "uk.gov.companieshouse.address-lookup.ingest",
  "detail-type": "ManifestReady",
  "detail": {
    "schemaVersion": 1,
    "releaseId": "f3d8c9a7-7b4e-4f62-a9d1-8c5e72f1b6a4",
    "object": {
      "bucket": "<ingest bucket>",
      "key": "acquisitions/2026-10-15/f3d8c9a7-7b4e-4f62-a9d1-8c5e72f1b6a4.json",
      "versionId": "m1"
    }
  }
}
```

Infected or inconclusive manifests publish `StepRejected` with `step: "manifest-scan"`,
`code: "MALWARE_FOUND"` or `"SCAN_INCONCLUSIVE"` and the same release/object reference, with
no `dataset` field. As with other files, invalid manifest inputs publish `INVALID_INPUT`.

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
      "key": "Scanned/12345678-1234-1234-1234-123456789abc/add_gb_builtaddress.zip",
      "versionId": "v1"
    },
    "step": "zip-scan",
    "code": "MALWARE_FOUND",
    "message": "Example.Malware"
  }
}
```

| Field | Values |
|---|---|
| `step` | `zip-scan`, `csv-scan`, `receipt-scan` or `manifest-scan`; `scan` when the key cannot be parsed |
| `code` | `MALWARE_FOUND`, `SCAN_INCONCLUSIVE` or `INVALID_INPUT` |
| `message` | Threat names (or `THREATS_FOUND` if names are unavailable), the inconclusive result (or `Missing scan result`), or the input-validation reason |

For invalid input, `releaseId`, `dataset` and `object` are omitted if the key cannot be parsed.
If the key parses, the object contains the available bucket, key and version ID fields; missing
fields are omitted. No `ExtractRequested`, `DatasetReady`, `ReceiptReady` or `ManifestReady`
is published for a rejected input.
Duplicate deliveries can publish the same event again. A failed publication throws so the
invocation can be retried.
