# s3

Acquisition uses existing S3 buckets with `control/` and `landing/`
sub-prefixes. This group does not create buckets or manage their policy, lifecycle,
versioning, encryption, ownership, public-access settings, tags or access logging.
Those bucket-wide settings remain with the bucket's current infrastructure owner.
S3 folders are key prefixes; no empty folder objects are needed.

## Configuration

The development (`cidev`) profile targets
`development-eu-west-2.resources.ch.gov.uk`, using
`alias/development-eu-west-2/environment-services-kms`.
Staging and live target `shared-services.eu-west-2.resources.ch.gov.uk`:

| Environment | Control prefix | Landing prefix |
| --- | --- | --- |
| Development | `control/` | `landing/` |
| Staging | `address-lookup/staging/control/` | `address-lookup/staging/landing/` |
| Live | `address-lookup/live/control/` | `address-lookup/live/landing/` |

Staging and live must each be configured with the shared bucket's
customer-managed KMS **key ARN** as `kms_key_id` before planning or deployment.
The bucket ARN is not a KMS key ARN. `shared_bucket = true` requires this
explicit ARN and an environment-specific namespace.
For a local development plan, supply the configured profile:

```bash
terraform plan -input=false \
  -var-file=profiles/development-eu-west-2/cidev/vars
```

The bucket must be in the same region as the relevant group. For same-account
buckets, set `kms_key_id` to the customer-managed encryption key ARN, ID or alias
if it differs from `alias/<aws_profile>/environment-services-kms`.
This group reads the key and grants access through IAM; it does not change
encryption settings, re-encrypt objects or update the shared key policy.

The development bucket currently has versioning enabled but defaults to
SSE-S3 (`AES256`). **KMS encryption remains a deployment requirement**: its owner
must change default encryption to the configured customer-managed key before
acquisition is deployed. This group does not make that bucket-wide change.
Existing objects and versions are not re-encrypted by changing the default.
An existing GuardDuty plan must also be checked by someone with
`guardduty:ListMalwareProtectionPlans` access; the development SSO role used for
inspection does not have that permission.

The shared-services bucket also currently defaults to SSE-S3 (`AES256`).
Its versioning and ownership settings could not be verified with the available
development role. Before deployment, the bucket owner must confirm the owning
account, enable versioning, arrange the required KMS encryption and cross-account
S3 and KMS permissions, and ensure the two namespaces are available and isolated.
The owner must explicitly permit the staging and live roles in the bucket and
key policies; IAM permissions in the environment accounts alone are insufficient.
Review existing broad grants and explicit denies in the bucket and role policies:
prefix-scoped permissions from this group alone do not prevent access granted
elsewhere.
Ensure that the chosen key and its policy permit each role's `kms:DescribeKey`,
`kms:Decrypt` and `kms:GenerateDataKey` as needed, and that the Terraform
deployment roles can read the bucket and key metadata.
Changing shared default encryption affects other bucket users and requires owner
approval; existing object versions are not re-encrypted automatically.

The `source_bucket`, `bucket_names` and `bucket_arns` outputs reference the existing
bucket. The latter two retain the `landing` map key for consumer compatibility.
`kms_key_arn` exposes the resolved key; `acquisition_prefixes` exposes the actual
control and landing paths.

## Requirements for the bucket owner

Before deployment, confirm:

- Versioning is enabled; acquisition relies on immutable object version IDs.
- Block Public Access, `BucketOwnerEnforced` ownership, default SSE-KMS using the
  configured customer-managed key, and a bucket policy denying non-TLS requests
  are in place.
- Server access logging is enabled to the existing account logging bucket.
- The key policy permits Download and GuardDuty roles directly or delegates
  permissions to the appropriate account IAM policies. Existing versions encrypted with other keys
  require additional permissions or a separate migration.
- Lifecycle rules are merged into the bucket owner's **single** lifecycle
  configuration, scoped only to these prefixes:

| Prefix | Recommended lifecycle |
| --- | --- |
| `<environment root>control/` | Retain current plans, cursors and receipts for recovery; expire noncurrent versions after 365 days. Never scanned. |
| `<environment root>landing/` | Expire current and noncurrent objects after 30 days. Scanned by GuardDuty. |

Abort incomplete multipart uploads after seven days under both prefixes.
Check existing whole-bucket rules: they also apply to these objects and could
delete control state prematurely. Reserve these prefixes for acquisition;
permissions cover all objects beneath them.

## Download and GuardDuty

`download_storage_policy_json` grants prefix-scoped listing, reading (including
versions), writing and multipart-upload aborts under the environment's control and landing prefixes.
It grants no deletion or access to other prefixes. KMS decrypt/data-key permissions
are restricted to the configured key through S3 in this region.
Pass the output to the shared Lambda module's `additional_policies`, following
`terraform/groups/db-migrate/main.tf`. This group does not deploy Download or
attach its policy.

For development, GuardDuty gets its own IAM role and Malware Protection plan.
Only the landing prefix is scanned and tagged; the control prefix is excluded.
`guardduty_scan_prefixes` can narrow scanning to one to five literal prefixes
beneath the configured landing prefix.
GuardDuty also needs the bucket-root
`malware-protection-resource-validation-object` for ownership validation.

For staging and live, `manage_guardduty_plan = false`: the shared bucket owner
must manage **one** plan covering both environment landing prefixes with tagging
enabled, without scanning either control prefix. Confirm whether a plan already
exists and merge the requirements into it rather than creating a second plan.
Its role must have access to both landing prefixes and the relevant KMS keys.
The environment outputs for the locally managed GuardDuty plan and role are null.

Coordinate with the bucket owner before enabling the plan: GuardDuty enables
S3 EventBridge notifications using its role's notification permissions. The owner
must preserve that setting alongside existing notifications. Only one Malware
Protection plan can protect a bucket; if one already exists, coordinate reuse or
import that plan and its role rather than deploying a duplicate.

## State transition and deployment

Terraform **1.7 or later** is required. `removed.tf` forgets bucket resources
previously managed by this group with `destroy = false`, preserving deployed
buckets, settings and contents. This also covers old Control/Quarantine instances
of `aws_s3_bucket.this` and the former access-logging module. Review the plan's
state-removal actions and arrange ownership/import in the appropriate bucket
stack. No objects are moved, and unused buckets must be retired separately.

Existing locally managed GuardDuty resources are protected from destruction.
Review the actual remote-state plan before applying: moving ownership of any
previously managed plan requires a deliberate state transfer to the bucket-owner
stack, not two stacks managing it. Changing a protected bucket may require
replacement and a scan-gap plan.
Apply through the `<env>-s3-plan` / `<env>-s3-apply` Concourse jobs only after the
bucket name, key, owner prerequisites and Terraform version are confirmed.

This is infrastructure preparation only. Java handlers retain their current S3
paths (`manifests/`, `ready/`, `quarantine/`, etc.) and bucket settings. They are
**not yet compatible** with the `control/` / `landing/` layout and must be adapted
before acquisition runs against it. Do not point current handlers at this layout
and assume the infrastructure change migrates their state.
