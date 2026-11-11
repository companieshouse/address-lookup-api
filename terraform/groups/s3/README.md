# s3

Acquisition storage for address-lookup: `address-lookup-<env>-control`, `-landing` and `-quarantine`.

All buckets have versioning (Extraction and Upload pin to `versionId`), Block Public Access,
`BucketOwnerEnforced` ownership, SSE-S3 and a bucket policy denying requests without TLS.

| Bucket | Lifecycle |
| --- | --- |
| control | `sources/acquisitions/` expires after `audit_retention_days` (365). Separate so it is never scanned. |
| landing | `raw/` and `extracted/` expire after `landing_retention_days` (30). |
| quarantine | everything expires after `landing_retention_days`. Only `quarantine_lambda_role_name` can write; only `break_glass_role_name` can read (explicit denies for everyone else). |

Retention periods and prefix placement are defaults pending agreement in ALS-102.
The two roles are referenced by ARN only and need not exist when this group is applied.

Apply with the `<env>-s3-plan` / `<env>-s3-apply` Concourse jobs.
