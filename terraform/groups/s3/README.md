# s3

Each environment creates an acquisition bucket in its own AWS account and region,
named `address-lookup-<environment>-<account-id>-landing`. The account ID
distinguishes these new buckets from the earlier `address-lookup-<environment>-landing`
names. S3 names are globally unique, so availability must still be confirmed.
No shared-services bucket is created or modified.

| Environment | Control prefix | Landing prefix |
| --- | --- | --- |
| Development (`cidev`) | `address-lookup/cidev/control/` | `address-lookup/cidev/landing/` |
| Staging | `address-lookup/staging/control/` | `address-lookup/staging/landing/` |
| Live | `address-lookup/live/control/` | `address-lookup/live/landing/` |

Prefixes are S3 object keys, not directories; no empty folder objects are created.
The `source_bucket`, `bucket_names` and `bucket_arns` outputs identify the new
bucket. `acquisition_prefixes` provides the environment's control and landing
paths. The latter two bucket outputs retain the `landing` map key for consumers.

## Bucket settings

The bucket has versioning, Block Public Access, `BucketOwnerEnforced` ownership,
default SSE-KMS and a bucket policy denying non-TLS requests. It uses the existing
customer-managed `alias/<aws_profile>/environment-services-kms` key by default;
set `kms_key_id` to another key ARN, ID or alias if needed. The key must exist
in the account and region, and its policy must permit the relevant roles through
IAM or directly. `kms_key_arn` exposes the selected key.

Server access logs go to the existing
`<account>-<region>-s3-access-logs.ch.gov.uk` bucket under
`<account-id>/<region>/<source-bucket>/`. The destination must already exist in
the same account and region and permit S3 log delivery. This group does not
create the log destination.

| Prefix | Lifecycle |
| --- | --- |
| `<environment root>control/` | Retain current plans, cursors and receipts; expire noncurrent versions after `audit_retention_days` (365). |
| `<environment root>landing/` tagged `retention=download` | Expire current objects after `landing_retention_days` (30); expire noncurrent versions after `noncurrent_retention_days` (30). |

Incomplete multipart uploads are aborted after one day under Landing and seven
days under Control. Untagged Landing objects, including manifests and archives,
have no expiry configured. The application does not yet add the download tag,
so download expiry will not work until uploading and tagging are integrated.
Manifest and archive retention periods remain to be agreed.

## Database import

This group creates an IAM role associated with the existing
`<environment>-address-rds` Aurora cluster for `s3Import`. It can read only
the Landing prefix and decrypt with the selected bucket key; that key's policy
must allow these permissions through IAM. Apply the Aurora group first.
The role alone does not enable imports: the database still needs the `aws_s3`
and `aws_commons` extensions and appropriate database grants. The Aurora group
adds S3 HTTPS egress, but endpoint routing and policy must also allow access.
Those prerequisites and an actual import have not yet been verified.

## Download and GuardDuty

`download_storage_policy_json` grants prefix-scoped listing, reading (including
versions), writing and multipart-upload aborts under the environment's control
and landing prefixes, plus KMS decrypt and data-key permissions through S3.
It does not grant deletion or access to other prefixes. Pass it to the shared
Lambda module's `additional_policies`, following
`terraform/groups/db-migrate/main.tf`; this group does not deploy Download.

GuardDuty is being implemented separately on `feature/als-104-guardduty`.
This group does not configure scan protection. The separate integration must
scan the corresponding landing prefix and exclude control.

## Deployment and state

Terraform 1.3 or later is required. Review each environment's real remote-state
plan before applying; do not assume previously deployed resources or state are
absent. The new bucket is protected by `prevent_destroy`; changing or retiring
it requires an explicit migration decision.

Terraform 1.3 cannot use `removed` blocks. Before applying, inspect each
environment's remote state and, if it contains any of the former bucket resource
addresses below, back up the state and remove those addresses from state using
`terraform state rm` with that environment's backend and workspace selected.
Do not delete the resources themselves. The pipeline rejects plans containing
deletions; do not bypass that check. The affected addresses are:

```
aws_s3_bucket.this
aws_s3_bucket_logging.this
module.access_logging
aws_s3_bucket_versioning.this
aws_s3_bucket_public_access_block.this
aws_s3_bucket_ownership_controls.this
aws_s3_bucket_server_side_encryption_configuration.this
aws_s3_bucket_policy.this
aws_s3_bucket_lifecycle_configuration.landing
aws_s3_bucket_lifecycle_configuration.control
aws_s3_bucket_lifecycle_configuration.quarantine
```

The new resources use distinct Terraform addresses and bucket names; old buckets
and shared-services contents are not moved or deleted. If data exists there,
arrange and verify a separate migration. If GuardDuty resources are already
managed in this group's state, removing their configuration may plan their
destruction; coordinate any state transfer and scanning transition before applying.

Apply with the `<env>-s3-plan` / `<env>-s3-apply` Concourse jobs after confirming
the bucket name, key, logging destination and state transition.

This is infrastructure preparation only. Java handlers currently use separate
Control and Landing buckets with keys such as `cursors/` and
`daily_cou/<package>/<version>/`. They do not prepend the environment-specific
prefixes above. Their bucket settings and key handling must be adapted before
running acquisition: the current prefix-scoped permissions, lifecycle rules and
planned GuardDuty scans do not cover those keys. Changing the infrastructure
does not migrate state.
