# s3

Each environment creates an acquisition bucket in its own AWS account and region,
named `address-lookup-<environment>-<account-id>-landing`. The account ID
distinguishes these new buckets from the earlier `address-lookup-<environment>-landing`
names. S3 names are globally unique, so availability must still be confirmed.
No shared-services bucket is created or modified.

| Environment | Control prefix | Landing prefix |
| --- | --- | --- |
| Development (`cidev`) | `control/` | `landing/` |
| Staging | `control/` | `landing/` |
| Live | `control/` | `landing/` |

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
| `control/` | Retain current plans, cursors and receipts; expire noncurrent versions after `audit_retention_days` (365). |
| `landing/` tagged `retention=download` | Expire current objects after `landing_retention_days` (30); expire noncurrent versions after `noncurrent_retention_days` (30). |

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

Each environment profile enables a GuardDuty Malware Protection plan for the
entire `landing/` prefix, with scan-result tagging enabled. Control is excluded.
The key policy must allow the GuardDuty role to use the configured KMS key.

Set `reconciliation_lambda_arns` to the deployed `extraction` and `import`
Lambda ARNs to create periodic wake-ups (default: every five minutes). These
are scheduled events, not rules on GuardDuty scan results. Both Lambdas need
permission to read the relevant S3 versions and their tags; the extraction
Lambda also needs Control write and EventBridge command-publishing permissions.
Configure both `CONTROL_BUCKET` and `LANDING_BUCKET` to the acquisition bucket.
Without both scheduled targets, pending scans and imports may not be revisited.
Do not connect the legacy Scan Lambda to GuardDuty scan-result events. Limit
concurrent extraction reconciliations to one to avoid overlapping sweeps.

This group does not configure or verify CH's GuardDuty findings-to-Ops routing.
Before deployment, confirm that GuardDuty is enabled in the bucket's account
and region (without a detector, malware scans do not create findings) and that the account's
GuardDuty findings reach the existing CH Ops channel. Do not add a separate
scan-result rule or alert topic here.

After deployment, upload a clean file and the approved EICAR test file under
`landing/`, and check their version-specific `GuardDutyMalwareScanStatus` tags
are `NO_THREATS_FOUND` and `THREATS_FOUND` respectively. Confirm Ops received
the finding and an object uploaded under `control/` was not scanned. GuardDuty
scans new uploads; existing objects must be handled as a separate migration.

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
arrange and verify a separate migration. Existing locally managed GuardDuty
resources are also protected from destruction; coordinate any state transfer
and scanning transition before applying.

Apply with the `<env>-s3-plan` / `<env>-s3-apply` Concourse jobs after confirming
the bucket name, key, logging destination and state transition.

The Lambdas use `control/` and `landing/` within the same acquisition bucket.
Changing prefixes or bucket names does not migrate existing objects or state.
