# s3

One acquisition bucket: `address-lookup-<env>-landing`, containing `control/`
and `landing/` prefixes. No separate Control or Quarantine buckets are created.
The existing Landing bucket name and Terraform resource address are retained.

The bucket has versioning (Extraction and Upload pin to `versionId`), Block Public Access,
`BucketOwnerEnforced` ownership, default SSE-KMS encryption using the existing
customer-managed `alias/<aws_profile>/environment-services-kms` key, and a bucket
policy denying requests without TLS.
S3 folders are key prefixes, so no empty folder objects are required.

Server access logging follows the shared `aws/s3_access_logging` convention and writes
to the existing `<account>-<region>-s3-access-logs.ch.gov.uk` bucket, with the
prefix `<account-id>/<region>/<source-bucket>/`. The account name is derived
from `aws_profile` by removing the region suffix (for example,
`development-eu-west-2` becomes `development`).
The destination must already exist in the same AWS account and region and allow
S3 server access log delivery. This group does not create another bucket for logs.
The logging resource is declared directly so Trivy can associate it with the
source bucket without environment profile variables. A `moved` block preserves
any logging configuration previously managed by the module.

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
It also allows `kms:GenerateDataKey` and `kms:Decrypt` on the encryption key,
only through S3 in the configured region, including for multipart uploads.

Pass this JSON output to the shared Lambda module's `additional_policies` when
deploying Download, following `terraform/groups/db-migrate/main.tf`.
The policy document lives in `data.tf`; bucket resources remain in `main.tf`.
This group does not create or attach a Download IAM policy, or create a Lambda
or its execution role.
GuardDuty has its own role, restricted to scanning and tagging `landing/`.
Its policy uses the same KMS key automatically, including permission to encrypt
the GuardDuty validation object.

The existing key must be available in the bucket's region, and its key policy
must permit these roles directly or delegate access through account IAM policies.
This group does not change the shared key's policy or rotation settings.
Other Lambda roles accessing encrypted data also need appropriate KMS permissions;
the key ARN is exposed as `kms_key_arn`.

## Deployment

This is infrastructure preparation only: Java handlers and their existing S3
paths are unchanged. Before running acquisition against this layout, update the
handlers to use the two prefixes and configure their bucket settings to this
bucket (`source_bucket` output). Completion/retry tracking remains application
logic; creating the bucket does not implement it.

Default SSE-KMS applies to new writes that do not override the encryption settings.
It does not re-encrypt existing objects or object versions; those require a separate
migration if present.

If the former Control or Quarantine buckets have already been deployed, migrate
or retain their contents and review the Terraform plan before applying. Removing
their configuration schedules those buckets for deletion; Terraform will refuse
to delete non-empty buckets. No objects are automatically moved.

Apply with the `<env>-s3-plan` / `<env>-s3-apply` Concourse jobs.

The group can be scanned with Trivy without loading a profile:

```bash
trivy config terraform/groups/s3
```
