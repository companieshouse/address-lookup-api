# terraform/groups/lambda

Infrastructure for the Lambda functions in `lambdas/` in this repository: the
Ordnance Survey address data acquisition pipeline, and the schema migrator that
is the only thing allowed to change the Aurora schema (see
[Schema migrator](#schema-migrator)).

## What the Lambda code expects

The five acquisition functions in `lambdas/` are wake-up handlers. None of them holds state
in memory between invocations and none of them trusts the event that woke it
up. All of the authoritative state is durable and lives in S3, which is why
each handler can be retried, duplicated, or replayed without harm.

That design only works if the surrounding AWS resources exist and behave in a
specific way. This group creates them.

| Code expects | Resource created here |
| ------------ | --------------------- |
| `SOURCE_BUCKET`, versioned | `aws_s3_bucket.source` plus `aws_s3_bucket_versioning.source` |
| `SCANNED_BUCKET`, versioned | `aws_s3_bucket.scanned` plus `aws_s3_bucket_versioning.scanned` |
| `EVENT_BUS` carrying `os.acquisition` commands | `aws_cloudwatch_event_bus.acquisition` |
| Objects tagged `GuardDutyMalwareScanStatus` | `aws_guardduty_malware_protection_plan.source` / `.scanned` |
| Objects encrypted with a key GuardDuty may use | `module.acquisition_kms` (customer managed key) |
| `OS_API_KEY`, `DB_USER`, `DB_PASSWORD` | HashiCorp Vault, read at apply time |
| `JDBC_URL` | derived from the cluster created by `terraform/groups/aurora` |
| "OnFailure DLQ" referenced in `ScanResultsService` | `aws_sqs_queue.dead_letter` plus an alarm |
| Outbound HTTPS to `api.os.uk` | Lambda security group egress via the application subnets |

Two details are easy to miss and both fail closed rather than loudly:

* **Versioning is mandatory.** `ReleaseStore` records the object version in
  every receipt and pins that version on later reads, so that bytes certified
  clean cannot be swapped before they are used. On an unversioned bucket
  `ReleaseStore.version` throws `Versioned bucket required`.
* **Without a GuardDuty malware protection plan nothing is ever tagged.**
  `ReleaseStore.scan` treats a missing tag as `PENDING`, so the pipeline simply
  stops after the first download with no error anywhere.

## Flow

```
            schedule
               |
               v
         [ discovery ]  writes source/acquisitions/<target>/<releaseId>.json
               |         and emits one DOWNLOAD command per dataset
        DOWNLOAD command
               |
               v
         [ download ]   streams the OS ZIP to source/quarantine/zips/...
               |         and writes a download receipt
          GuardDuty tags the object
               |
               v
         [ scan ]       promotes the plan to scanned/manifests/, then issues
               |         whichever command is still outstanding
         UNZIP command
               |
               v
         [ unzip ]      expands the primary CSV to scanned/quarantine/csv/...
               |
          GuardDuty tags the CSV
               |
               v
         [ scan ]       writes scanned/ready/<releaseId>/<dataset>.json
               |
               v
         [ import ]     COPYs into Aurora and promotes the release
```

`scan` and `import` also run on a timer. Those sweeps, not the events, are what
make the pipeline reliable: a command is only ever an optimisation, and a lost
event costs at most one reconciliation interval.

## Security posture

The four acquisition functions form a red zone. They handle bytes that have not
yet been scanned, so they are given no database configuration and no route to
Aurora — `AcquisitionConfiguration` is explicit that it wires no database, and
the IAM policies in `data.tf` match that.

`import` is the only function with credentials, the only one whose security
group is authorised on the Aurora security group, and the only one pinned to a
reserved concurrency of one so that it is the single writer.

Each function's policy grants only the S3 prefixes it reads and writes.
`s3:ListBucket` is granted at bucket scope because `ReleaseStore` probes for an
exact key before reading it rather than relying on the difference between a 404
and a 403.

### Encryption

Both buckets use SSE-KMS with a customer managed key from
`terraform-modules//aws/kms`. GuardDuty Malware Protection can scan objects
under a customer managed key as long as the protection plan's role is allowed
to use it. It cannot scan objects under the AWS managed `aws/s3` key. The key
policy delegates to the account, and access comes from IAM: the GuardDuty role
and each function's execution role get `kms:Decrypt` and
`kms:GenerateDataKey`, usable only through S3 (`kms:ViaService`).

## Schema migrator

`schema-migrator.tf` deploys `address-lookup-schema-migrator-<environment>`,
which applies a released `db-schema` changelog to Aurora with Liquibase. The
end-to-end design, pipeline and runbook are in
[`db-schema/README.md`](../../../db-schema/README.md). In outline:

| Concern | How it is handled here |
| ------- | ---------------------- |
| Trigger | No event sources and no resource policy. Concourse invokes it synchronously after a `db-schema` release |
| Credentials | Aurora master credentials from Vault (`common-services-stack/address-rds`), copied by `terraform-modules//aws/parameter-store` into two SecureStrings under a dedicated key (`module.schema_migrator_kms`). The function reads them at invocation time. `lambda_ssm_parameter_arns` and a `kms:ViaService`/`PARAMETER_ARN` scoped `kms:Decrypt` limit it to those two parameters |
| Changelog | `s3:GetObject` on `address-lookup-api/address-lookup-db-schema-*.zip` in the release bucket only. The function also checks the SHA-256 that the pipeline passes in |
| Network | Egress on 443 (S3, Parameter Store) and on the database port to the Aurora security group only. Aurora gets a matching ingress rule |
| Concurrency | Reserved concurrency of 1 and no retries. One migration at a time; Concourse decides what to do after a failure |
| Timeout | 900 seconds. `UPDATE` stops `MIN_REMAINING_MILLIS` before that and returns `PARTIAL`, so the Liquibase lock is always released |

It uses the master user because `CREATE EXTENSION postgis` needs
`rds_superuser`. A dedicated migration role with that grant is a follow-up.

## How this follows Companies House conventions

| Resource | Convention followed | Reference |
| -------- | ------------------- | --------- |
| S3 buckets | Plain `aws_s3_bucket*` resources (there is no shared bucket module), with public access blocked, `BucketOwnerEnforced` ownership, a TLS-only bucket policy, and server access logging through `terraform-modules//aws/s3_access_logging` to the account's `<aws_account>-<region>-s3-access-logs.ch.gov.uk` bucket | `file-transfer-stack`, `call-centre-data-terraform`, `cdn-terraform` |
| GuardDuty Malware Protection | Plain `aws_guardduty_malware_protection_plan` resources with a dedicated role whose policy follows the GuardDuty user guide template, and a customer managed key the role may use | `file-transfer-stack/groups/s3-av` (the only other use in the organisation) |
| KMS | `terraform-modules//aws/kms` | `identity-verification-api`, `notifications-service-stack` |
| EventBridge rules | Created through the `lambda_cloudwatch_event_rules` input of `terraform-modules//aws/lambda` | `s3-av-scanner`, `payment-reconciler` (plain rules) |
| EventBridge bus | Plain `aws_cloudwatch_event_bus`. No other repository in the organisation creates a custom bus and there is no shared module for one | none |
| SQS | Plain `aws_sqs_queue` resources | `efs-document-processor` |
| Lambda | `terraform-modules//aws/lambda`, artefact `<name>-<version>.zip` from the release bucket, built as a shaded `-lambda.jar` | `search-service-comparison-utility` |
| Parameter Store | `terraform-modules//aws/parameter-store` fed from Vault | `search-service-comparison-utility` |

## Deployment

Applied by the `address-lookup-api` pipeline in `companieshouse/ci-pipelines`
on the `terraform/` path, with `GROUP: lambda`, in the same way as the existing
`aurora` and `ecs-service` groups. `lambda_version` selects which release
artefacts are deployed; all six functions are built from one Maven reactor and
therefore share a version.

Secrets are read from Vault at
`applications/<aws_profile>/<environment>/common-services-stack/address-lookup-api`
and must include `os_api_key`, `importer_db_user` and `importer_db_password`.
The schema migrator also reads `master_username` and `master_password` from
`applications/<aws_profile>/<environment>/common-services-stack/address-rds`,
the same secret the `aurora` group creates the cluster with.

## Known follow-ups

* `lambdas/scan` declares its handler in package `uk.goc.companieshouse...`
  rather than `uk.gov.companieshouse...`. `locals.tf` tracks the code as it
  stands so the function is deployable; remove the special case in
  `local.handler` once the package is corrected.
* The import function's `JDBC_URL` points `sslrootcert` at `/opt/rds-ca.pem`,
  which nothing provides. The schema migrator packages the RDS CA bundle in its
  jar instead; the importer should do the same.
* `var.os_packages` is empty by default and must be populated with the OS Data
  Hub package identifiers before `discovery` can run.

<!-- BEGIN_TF_DOCS -->
## Requirements

| Name | Version |
| ---- | ------- |
| <a name="requirement_terraform"></a> [terraform](#requirement\_terraform) | >= 1.3, < 2.0 |
| <a name="requirement_aws"></a> [aws](#requirement\_aws) | >= 6.0, < 7.0 |
| <a name="requirement_vault"></a> [vault](#requirement\_vault) | >= 5.0, < 6.0 |

## Providers

| Name | Version |
| ---- | ------- |
| <a name="provider_aws"></a> [aws](#provider\_aws) | >= 6.0, < 7.0 |
| <a name="provider_vault"></a> [vault](#provider\_vault) | >= 5.0, < 6.0 |

## Modules

| Name | Source | Version |
| ---- | ------ | ------- |
| <a name="module_acquisition_kms"></a> [acquisition\_kms](#module\_acquisition\_kms) | git@github.com:companieshouse/terraform-modules//aws/kms | 1.0.434 |
| <a name="module_iac_tags"></a> [iac\_tags](#module\_iac\_tags) | git@github.com:companieshouse/terraform-modules//aws/tagging/iac | 1.0.434 |
| <a name="module_lambda"></a> [lambda](#module\_lambda) | git@github.com:companieshouse/terraform-modules.git//aws/lambda | 1.0.434 |
| <a name="module_owner_tags"></a> [owner\_tags](#module\_owner\_tags) | git@github.com:companieshouse/terraform-modules//aws/tagging/owner | 1.0.434 |
| <a name="module_scanned_s3_access_logging"></a> [scanned\_s3\_access\_logging](#module\_scanned\_s3\_access\_logging) | git@github.com:companieshouse/terraform-modules//aws/s3_access_logging | 1.0.434 |
| <a name="module_schema_migrator"></a> [schema\_migrator](#module\_schema\_migrator) | git@github.com:companieshouse/terraform-modules.git//aws/lambda | 1.0.434 |
| <a name="module_schema_migrator_kms"></a> [schema\_migrator\_kms](#module\_schema\_migrator\_kms) | git@github.com:companieshouse/terraform-modules//aws/kms | 1.0.434 |
| <a name="module_schema_migrator_secrets"></a> [schema\_migrator\_secrets](#module\_schema\_migrator\_secrets) | git@github.com:companieshouse/terraform-modules//aws/parameter-store | 1.0.434 |
| <a name="module_source_s3_access_logging"></a> [source\_s3\_access\_logging](#module\_source\_s3\_access\_logging) | git@github.com:companieshouse/terraform-modules//aws/s3_access_logging | 1.0.434 |

## Resources

| Name | Type |
| ---- | ---- |
| [aws_cloudwatch_event_bus.acquisition](https://registry.terraform.io/providers/hashicorp/aws/latest/docs/resources/cloudwatch_event_bus) | resource |
| [aws_cloudwatch_metric_alarm.dead_letter](https://registry.terraform.io/providers/hashicorp/aws/latest/docs/resources/cloudwatch_metric_alarm) | resource |
| [aws_guardduty_malware_protection_plan.scanned](https://registry.terraform.io/providers/hashicorp/aws/latest/docs/resources/guardduty_malware_protection_plan) | resource |
| [aws_guardduty_malware_protection_plan.source](https://registry.terraform.io/providers/hashicorp/aws/latest/docs/resources/guardduty_malware_protection_plan) | resource |
| [aws_iam_role.guardduty_malware_protection](https://registry.terraform.io/providers/hashicorp/aws/latest/docs/resources/iam_role) | resource |
| [aws_iam_role_policy.guardduty_malware_protection](https://registry.terraform.io/providers/hashicorp/aws/latest/docs/resources/iam_role_policy) | resource |
| [aws_s3_bucket.scanned](https://registry.terraform.io/providers/hashicorp/aws/latest/docs/resources/s3_bucket) | resource |
| [aws_s3_bucket.source](https://registry.terraform.io/providers/hashicorp/aws/latest/docs/resources/s3_bucket) | resource |
| [aws_s3_bucket_lifecycle_configuration.scanned](https://registry.terraform.io/providers/hashicorp/aws/latest/docs/resources/s3_bucket_lifecycle_configuration) | resource |
| [aws_s3_bucket_lifecycle_configuration.source](https://registry.terraform.io/providers/hashicorp/aws/latest/docs/resources/s3_bucket_lifecycle_configuration) | resource |
| [aws_s3_bucket_ownership_controls.scanned](https://registry.terraform.io/providers/hashicorp/aws/latest/docs/resources/s3_bucket_ownership_controls) | resource |
| [aws_s3_bucket_ownership_controls.source](https://registry.terraform.io/providers/hashicorp/aws/latest/docs/resources/s3_bucket_ownership_controls) | resource |
| [aws_s3_bucket_policy.scanned_tls_only](https://registry.terraform.io/providers/hashicorp/aws/latest/docs/resources/s3_bucket_policy) | resource |
| [aws_s3_bucket_policy.source_tls_only](https://registry.terraform.io/providers/hashicorp/aws/latest/docs/resources/s3_bucket_policy) | resource |
| [aws_s3_bucket_public_access_block.scanned](https://registry.terraform.io/providers/hashicorp/aws/latest/docs/resources/s3_bucket_public_access_block) | resource |
| [aws_s3_bucket_public_access_block.source](https://registry.terraform.io/providers/hashicorp/aws/latest/docs/resources/s3_bucket_public_access_block) | resource |
| [aws_s3_bucket_server_side_encryption_configuration.scanned](https://registry.terraform.io/providers/hashicorp/aws/latest/docs/resources/s3_bucket_server_side_encryption_configuration) | resource |
| [aws_s3_bucket_server_side_encryption_configuration.source](https://registry.terraform.io/providers/hashicorp/aws/latest/docs/resources/s3_bucket_server_side_encryption_configuration) | resource |
| [aws_s3_bucket_versioning.scanned](https://registry.terraform.io/providers/hashicorp/aws/latest/docs/resources/s3_bucket_versioning) | resource |
| [aws_s3_bucket_versioning.source](https://registry.terraform.io/providers/hashicorp/aws/latest/docs/resources/s3_bucket_versioning) | resource |
| [aws_sqs_queue.dead_letter](https://registry.terraform.io/providers/hashicorp/aws/latest/docs/resources/sqs_queue) | resource |
| [aws_sqs_queue_policy.dead_letter](https://registry.terraform.io/providers/hashicorp/aws/latest/docs/resources/sqs_queue_policy) | resource |
| [aws_vpc_security_group_egress_rule.schema_migrator_to_aurora](https://registry.terraform.io/providers/hashicorp/aws/latest/docs/resources/vpc_security_group_egress_rule) | resource |
| [aws_vpc_security_group_ingress_rule.importer_to_aurora](https://registry.terraform.io/providers/hashicorp/aws/latest/docs/resources/vpc_security_group_ingress_rule) | resource |
| [aws_vpc_security_group_ingress_rule.schema_migrator_to_aurora](https://registry.terraform.io/providers/hashicorp/aws/latest/docs/resources/vpc_security_group_ingress_rule) | resource |
| [aws_caller_identity.aws_identity](https://registry.terraform.io/providers/hashicorp/aws/latest/docs/data-sources/caller_identity) | data source |
| [aws_iam_policy_document.acquisition_kms](https://registry.terraform.io/providers/hashicorp/aws/latest/docs/data-sources/iam_policy_document) | data source |
| [aws_iam_policy_document.dead_letter_queue_policy](https://registry.terraform.io/providers/hashicorp/aws/latest/docs/data-sources/iam_policy_document) | data source |
| [aws_iam_policy_document.discovery](https://registry.terraform.io/providers/hashicorp/aws/latest/docs/data-sources/iam_policy_document) | data source |
| [aws_iam_policy_document.download](https://registry.terraform.io/providers/hashicorp/aws/latest/docs/data-sources/iam_policy_document) | data source |
| [aws_iam_policy_document.guardduty_malware_protection](https://registry.terraform.io/providers/hashicorp/aws/latest/docs/data-sources/iam_policy_document) | data source |
| [aws_iam_policy_document.guardduty_malware_protection_trust](https://registry.terraform.io/providers/hashicorp/aws/latest/docs/data-sources/iam_policy_document) | data source |
| [aws_iam_policy_document.import](https://registry.terraform.io/providers/hashicorp/aws/latest/docs/data-sources/iam_policy_document) | data source |
| [aws_iam_policy_document.scan](https://registry.terraform.io/providers/hashicorp/aws/latest/docs/data-sources/iam_policy_document) | data source |
| [aws_iam_policy_document.scanned_bucket_policy](https://registry.terraform.io/providers/hashicorp/aws/latest/docs/data-sources/iam_policy_document) | data source |
| [aws_iam_policy_document.schema_migrator](https://registry.terraform.io/providers/hashicorp/aws/latest/docs/data-sources/iam_policy_document) | data source |
| [aws_iam_policy_document.source_bucket_policy](https://registry.terraform.io/providers/hashicorp/aws/latest/docs/data-sources/iam_policy_document) | data source |
| [aws_iam_policy_document.unzip](https://registry.terraform.io/providers/hashicorp/aws/latest/docs/data-sources/iam_policy_document) | data source |
| [aws_partition.current](https://registry.terraform.io/providers/hashicorp/aws/latest/docs/data-sources/partition) | data source |
| [aws_rds_cluster.aurora](https://registry.terraform.io/providers/hashicorp/aws/latest/docs/data-sources/rds_cluster) | data source |
| [aws_security_group.aurora](https://registry.terraform.io/providers/hashicorp/aws/latest/docs/data-sources/security_group) | data source |
| [aws_subnets.application](https://registry.terraform.io/providers/hashicorp/aws/latest/docs/data-sources/subnets) | data source |
| [aws_vpc.vpc](https://registry.terraform.io/providers/hashicorp/aws/latest/docs/data-sources/vpc) | data source |
| [vault_generic_secret.database_secrets](https://registry.terraform.io/providers/hashicorp/vault/latest/docs/data-sources/generic_secret) | data source |
| [vault_generic_secret.service_secrets](https://registry.terraform.io/providers/hashicorp/vault/latest/docs/data-sources/generic_secret) | data source |
| [vault_generic_secret.stack_secrets](https://registry.terraform.io/providers/hashicorp/vault/latest/docs/data-sources/generic_secret) | data source |

## Inputs

| Name | Description | Type | Default | Required |
| ---- | ----------- | ---- | ------- | :------: |
| <a name="input_acquisition_sweep_limit"></a> [acquisition\_sweep\_limit](#input\_acquisition\_sweep\_limit) | Maximum number of acquisition plans the scan Lambda reconciles per invocation | `number` | `20` | no |
| <a name="input_aws_account"></a> [aws\_account](#input\_aws\_account) | The AWS account name, e.g. development, staging or live. Used to locate the account's shared S3 access logging bucket. | `string` | n/a | yes |
| <a name="input_aws_profile"></a> [aws\_profile](#input\_aws\_profile) | The AWS profile to use for authentication, defined in environments vars. | `string` | n/a | yes |
| <a name="input_aws_region"></a> [aws\_region](#input\_aws\_region) | The region in which to provision resources | `string` | `"eu-west-2"` | no |
| <a name="input_discovery_schedule_expression"></a> [discovery\_schedule\_expression](#input\_discovery\_schedule\_expression) | Schedule on which the discovery Lambda looks for a new Ordnance Survey supply | `string` | `"cron(0 2 ? * MON *)"` | no |
| <a name="input_environment"></a> [environment](#input\_environment) | The environment name, defined in environments vars. | `string` | n/a | yes |
| <a name="input_hashicorp_vault_password"></a> [hashicorp\_vault\_password](#input\_hashicorp\_vault\_password) | The password used when retrieving configuration from Hashicorp Vault | `string` | n/a | yes |
| <a name="input_hashicorp_vault_username"></a> [hashicorp\_vault\_username](#input\_hashicorp\_vault\_username) | The username used when retrieving configuration from Hashicorp Vault | `string` | n/a | yes |
| <a name="input_importer_sweep_limit"></a> [importer\_sweep\_limit](#input\_importer\_sweep\_limit) | Maximum number of release manifests the import Lambda reconciles per invocation | `number` | `20` | no |
| <a name="input_lambda_artifact_key_prefix"></a> [lambda\_artifact\_key\_prefix](#input\_lambda\_artifact\_key\_prefix) | The prefix under which Lambda release artefacts are published in the release bucket | `string` | `"address-lookup-api"` | no |
| <a name="input_lambda_logs_retention_days"></a> [lambda\_logs\_retention\_days](#input\_lambda\_logs\_retention\_days) | The number of days to retain Lambda logs in CloudWatch | `number` | `90` | no |
| <a name="input_lambda_runtime"></a> [lambda\_runtime](#input\_lambda\_runtime) | The Lambda runtime to use for all functions | `string` | `"java21"` | no |
| <a name="input_lambda_version"></a> [lambda\_version](#input\_lambda\_version) | The version of the address-lookup Lambda artefacts to deploy. All six functions, including the schema migrator, are released together from the same reactor build and therefore share a version. | `string` | n/a | yes |
| <a name="input_max_extracted_bytes"></a> [max\_extracted\_bytes](#input\_max\_extracted\_bytes) | Largest total expanded size the unzip Lambda will tolerate for a single archive | `number` | `1073741824` | no |
| <a name="input_max_zip_bytes"></a> [max\_zip\_bytes](#input\_max\_zip\_bytes) | Largest Ordnance Survey ZIP the download Lambda will stream into the source bucket | `number` | `536870912` | no |
| <a name="input_os_packages"></a> [os\_packages](#input\_os\_packages) | Map of Ordnance Survey dataset name to OS Data Hub package identifier, supplied to the discovery Lambda as OS\_PACKAGES. | `map(string)` | `{}` | no |
| <a name="input_reconcile_schedule_expression"></a> [reconcile\_schedule\_expression](#input\_reconcile\_schedule\_expression) | Schedule on which the scan and import Lambdas sweep for work that no event delivered. This is the safety net that makes the pipeline self-healing. | `string` | `"rate(15 minutes)"` | no |
| <a name="input_release_bucket_name"></a> [release\_bucket\_name](#input\_release\_bucket\_name) | The name of the S3 bucket containing the release artefacts for the Lambda functions | `string` | n/a | yes |
| <a name="input_scanned_bucket_expiration_days"></a> [scanned\_bucket\_expiration\_days](#input\_scanned\_bucket\_expiration\_days) | Number of days after which scanned CSV objects are expired from the scanned bucket. Manifests and ready markers are small and are retained. | `number` | `90` | no |
| <a name="input_source_bucket_expiration_days"></a> [source\_bucket\_expiration\_days](#input\_source\_bucket\_expiration\_days) | Number of days after which quarantined downloads and extracts are expired from the source bucket | `number` | `30` | no |

## Outputs

| Name | Description |
| ---- | ----------- |
| <a name="output_dead_letter_queue_url"></a> [dead\_letter\_queue\_url](#output\_dead\_letter\_queue\_url) | URL of the queue holding asynchronous invocations that exhausted their retries |
| <a name="output_event_bus_name"></a> [event\_bus\_name](#output\_event\_bus\_name) | Name of the EventBridge bus carrying acquisition commands |
| <a name="output_importer_security_group_id"></a> [importer\_security\_group\_id](#output\_importer\_security\_group\_id) | Security group authorised to reach the Aurora cluster |
| <a name="output_lambda_function_names"></a> [lambda\_function\_names](#output\_lambda\_function\_names) | Deployed Lambda function names, keyed by pipeline stage |
| <a name="output_scanned_bucket_name"></a> [scanned\_bucket\_name](#output\_scanned\_bucket\_name) | Clean zone bucket holding certified manifests, ready markers and extracted CSV content |
| <a name="output_schema_migrator_function_name"></a> [schema\_migrator\_function\_name](#output\_schema\_migrator\_function\_name) | The function Concourse invokes to apply a released db-schema changelog to Aurora |
| <a name="output_source_bucket_name"></a> [source\_bucket\_name](#output\_source\_bucket\_name) | Red zone bucket holding acquisition plans, receipts and unverified Ordnance Survey downloads |
<!-- END_TF_DOCS -->
