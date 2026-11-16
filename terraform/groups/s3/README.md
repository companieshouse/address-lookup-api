# s3

One acquisition bucket: `address-lookup-<env>-landing`, containing `control/`
and `landing/` prefixes. No separate Control or Quarantine buckets are created.
The existing Landing bucket name and Terraform resource address are retained.

The bucket has versioning (Extraction and Upload pin to `versionId`), Block Public Access,
`BucketOwnerEnforced` ownership, SSE-S3 and a bucket policy denying requests without TLS.
S3 folders are key prefixes, so no empty folder objects are required.

| Prefix | Lifecycle |
| --- | --- |
| `control/` | Current plans, cursors and completion receipts are retained for recovery. Noncurrent versions expire after `audit_retention_days` (365). Never scanned. |
| `landing/` | Current objects expire after `landing_retention_days` (30); noncurrent versions expire after `noncurrent_retention_days` (30). Scanned by GuardDuty. |

Incomplete multipart uploads under either prefix are aborted after seven days.

## Download Lambda permissions

The `download_storage_policy_json` output describes prefix-scoped listing, reading
(including object versions), writing and multipart-upload aborts under `control/`
and `landing/` in this bucket. It does not grant deletion, public access or access
to other buckets/prefixes.

Pass this JSON output to the shared Lambda module's `additional_policies` when
deploying Download, following `terraform/groups/db-migrate/main.tf`.
The policy document lives in `data.tf`; bucket resources remain in `main.tf`.
This group does not create or attach a Download IAM policy, or create a Lambda
or its execution role.
GuardDuty has its own role, restricted to scanning and tagging `landing/`.

## Deployment

This is infrastructure preparation only: Java handlers and their existing S3
paths are unchanged. Before running acquisition against this layout, update the
handlers to use the two prefixes and configure their bucket settings to this
bucket (`source_bucket` output). Completion/retry tracking remains application
logic; creating the bucket does not implement it.

If the former Control or Quarantine buckets have already been deployed, migrate
or retain their contents and review the Terraform plan before applying. Removing
their configuration schedules those buckets for deletion; Terraform will refuse
to delete non-empty buckets. No objects are automatically moved.

Apply with the `<env>-s3-plan` / `<env>-s3-apply` Concourse jobs.
