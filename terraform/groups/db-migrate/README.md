# db-migrate

Deploys the Flyway migration Lambda `address-lookup-db-migrate-<env>` for the
address-lookup Aurora cluster (`<env>-address-rds`) in cidev, staging and live.

The `address-lookup-api` Concourse pipeline invokes the function
synchronously:

- `<env>-db-schema-migrate` runs `info`, `migrate` and `info` with the recorded
  `snapshots/<tag>.zip`.
- `cidev-apply`, `staging-apply` and `live-apply` run `info` (check-db-schema).
- `<env>-db-schema-repair` is break-glass only.

Nothing else triggers it.

A snapshot is created when a change to `database/migrations/` is merged to
main. `db-snapshot-release` then creates the next `address-lookup-db-X.Y.Z`
tag, using the major and minor versions in `database/version`, and
`gather-snapshot` uploads `snapshots/<tag>.zip`. To change the major or minor
version, edit `database/version`.

```
Concourse (concourse-platform) ──lambda:InvokeFunction──▶ address-lookup-db-migrate-<env>
                                                          │  data subnets, own SG, no CIDR egress
                       ┌──────────────────────────────────┼──────────────────────────────┐
                 443 ▼ S3 gateway endpoint          443 ▼ SSM interface endpoint    5432 ▼ Aurora SG
          shared-services releases bucket      /address-lookup/<env>/db/       <env>-address-rds
                  snapshots/*                       schema-owner                  (addressdb)
```

## What it creates

| Requirement (ALS-87) | Implementation |
|---|---|
| Function, java21, handler, 1024 MB, 15 min | `module.lambda` (terraform-modules `aws/lambda` 1.0.442), artefact `address-lookup-api/address-lookup-db-migrate-<version>.zip` from the release bucket, produced by `lambdas/db-migrate` and released by the pipeline's `db-migrate-lambda-release` job |
| Reserved concurrency 1 | `lambda_reserved_concurrent_executions = 1`. Async retries are 0. |
| Env `SNAPSHOT_BUCKET`, `DB_CREDENTIALS_PARAMETER`, `LOCK_TIMEOUT=10s`, `ALLOW_REPAIR=false` | `lambda_env_vars`, plus `AWS_ENDPOINT_URL_SSM` (see Network) |
| No triggers | No event source mappings, EventBridge rules, schedules or SNS/SQS subscriptions are declared |
| Invoke only by that environment's Concourse | Resource policy statement `AllowConcourseInvoke` for IAM user `var.concourse_iam_user_name` (`concourse-platform`) |
| Private subnets, own SG, no internet route | Data subnets (`data_subnet_pattern` from Vault). The SG has no CIDR egress. Its only egress rules go to Aurora 5432, the S3 gateway prefix list 443 and the SSM endpoint SG 443. |
| Aurora allows 5432 from it | `aws_vpc_security_group_ingress_rule.aurora_from_lambda` on `<env>-address-rds-rds-sg` |
| Endpoints S3 gateway, SSM, KMS, Logs | S3 gateway: the VPC's existing one, looked up. SSM: created here. KMS and Logs: not needed (see Network). |
| IAM S3 Get on `snapshots/*`, `ssm:GetParameter` on the one parameter, `kms:Decrypt` | `data.aws_iam_policy_document.db_migrate` attached via `additional_policies` |
| Credentials | SecureString `/address-lookup/<env>/db/schema-owner` = `{"username","password","jdbcUrl"}`, written from Vault by `module.credentials` (terraform-modules `aws/parameter-store`) and encrypted with `alias/<aws_profile>/environment-services-kms` |
| Log group | Created by the module (`/aws/lambda/address-lookup-db-migrate-<env>`), retention `lambda_logs_retention_days` (90) |
| Errors > 0 alarm to the team channel | `aws_cloudwatch_metric_alarm.errors` → `common-services-<env>-cloudwatch-alarms-notify-topic` (override with `alarm_topic_name`) |

### Deviations from the ticket, and why

- **Function name.** The ticket asks for `address-lookup-<env>-db-migrate`.
  The org `aws/lambda` module always names functions `<function_name>-<environment>`,
  so it is `address-lookup-db-migrate-<env>`. The pipeline uses this name.
- **SSM permission from the module.** The module's base role grants
  `ssm:GetParameters` (plural) on `*` for `LAMBDA_GLOBAL_SSM_PREFIX`. The
  function calls `GetParameter` (singular), which this group grants on the one
  parameter only. The SSM endpoint policy also allows only `GetParameter` on
  that parameter, and only for this role.
- **No KMS or Logs endpoints.**
  - Parameter Store decrypts the SecureString server-side (`WithDecryption`),
    using the role's `kms:Decrypt` grant, so the function never calls KMS.
  - The snapshot bucket uses SSE-S3, so S3 needs no KMS call either. If it
    moves to SSE-KMS, set `snapshot_kms_key_arn`.
  - Lambda ships the function's logs itself, outside the VPC.
- **SSM endpoint private DNS is off.** The VPC is shared. With private DNS on,
  every SSM call in the VPC (ECS agents, other services) would resolve to an
  endpoint that admits only this function. The function is instead given
  `AWS_ENDPOINT_URL_SSM`, which the AWS SDK for Java 2.x honours.
- **"Anyone else → AccessDenied".**
  - The resource policy grants only Concourse. A resource policy can only
    Allow, though, so principals *in the same account* whose identity policies
    allow `lambda:InvokeFunction` on `*` (for example SSO administrator roles)
    can still invoke it. Cross-account principals and same-account principals
    without such a policy get AccessDenied.
  - To close this completely, add an explicit Deny for
    `arn:aws:lambda:*:*:function:address-lookup-db-migrate-*` to the SSO
    permission sets, or as an SCP with an exception for `concourse-platform`.
- **"Only db-migrate, Import and API reach Aurora".** This group adds only the
  db-migrate rule. The API and Import ingress rules belong to the groups that
  own those services. The Aurora SG has no ingress rules today (cidev, checked
  2026-10-14).

## Prerequisites

1. `terraform/groups/aurora` is applied. This group looks up the cluster and
   its SG by name.
2. Vault `applications/<aws_profile>/<env>/common-services-stack/address-rds`
   holds `schema_owner_username` and `schema_owner_password`, as well as the
   existing `data_subnet_pattern`. The plan fails without them.
   - The same password is used when the role is created in the database
     (ALS-90).
   - To rotate it, change Vault, then re-apply this group and the ALS-90
     bootstrap.
3. The VPC has an S3 gateway endpoint associated with the data subnets' route
   tables. It is looked up; the plan fails if it is missing.
4. The release `address-lookup-api/address-lookup-db-migrate-<version>.zip`
   exists in the shared-services release bucket. To produce it, push a
   `db-migrate-lambda-X.Y.Z` release for `lambdas/db-migrate`
   (`make package-db-migrate-lambda`).
5. The environment's Concourse AWS credentials are the `concourse-platform` IAM
   user. If not, set `concourse_iam_user_name`.

## Deployment

Pipeline `address-lookup-api`:

| Environment | Jobs |
|---|---|
| cidev (automatic) | `cidev-db-migrate-plan`, then `cidev-db-migrate-apply` |
| staging (manual) | `staging-db-migrate-plan`, then `staging-db-migrate-apply` |
| live (manual) | `live-db-migrate-plan`, then `live-db-migrate-apply` |

- staging and live take the exact Lambda release and terraform that the
  previous environment applied.
- `<env>-db-schema-migrate` will not run until its environment's apply has succeeded.

Locally:

```sh
terraform init -backend-config=... && terraform plan \
  -var-file=profiles/development-eu-west-2/cidev/vars \
  -var release_bucket_name=... -var release_artifact_key=... -var snapshot_bucket_name=...
```

## Verifying the acceptance criteria

Run these from the environment's account (`F=address-lookup-db-migrate-<env>`).

```sh
# info from Concourse returns SUCCESS (run with the Concourse credentials, or
# re-run the <env>-db-schema-migrate job and read its info output)
aws lambda invoke --function-name "$F" --cli-binary-format raw-in-base64-out \
  --payload '{"command":"info","key":"snapshots/<tag>.zip","versionId":"<versionId>","sha256":"<sha256>"}' out.json
cat out.json        # "status":"SUCCESS", schemaVersion, pending

# Anyone else gets AccessDenied: the policy has a single Allow for concourse-platform
aws lambda get-policy --function-name "$F" --query Policy --output text | jq '.Statement'
# ...and, from another account or a role with no lambda:InvokeFunction grant:
aws lambda invoke --function-name "arn:aws:lambda:eu-west-2:<account>:function:$F" out.json   # AccessDeniedException

# No triggers
aws lambda list-event-source-mappings --function-name "$F" --query 'EventSourceMappings' # []
aws events list-rule-names-by-target --target-arn "$(aws lambda get-function --function-name "$F" --query Configuration.FunctionArn --output text)" # []

# Only db-migrate (and, once their groups add them, Import and API) reach Aurora
SG=$(aws ec2 describe-security-groups --filters Name=group-name,Values=<env>-address-rds-rds-sg --query 'SecurityGroups[0].GroupId' --output text)
aws ec2 describe-security-group-rules --filters Name=group-id,Values="$SG" \
  --query 'SecurityGroupRules[?!IsEgress].[FromPort,ReferencedGroupInfo.GroupId,CidrIpv4,Description]' --output table

# No internet access: the function SG's egress is only Aurora, the S3 prefix list and the SSM endpoint SG
LSG=$(terraform output -raw security_group_id)
aws ec2 describe-security-group-rules --filters Name=group-id,Values="$LSG" \
  --query 'SecurityGroupRules[?IsEgress].[FromPort,ReferencedGroupInfo.GroupId,PrefixListId,CidrIpv4]' --output table

# Concurrency 1: a second simultaneous invocation is throttled (TooManyRequestsException)
aws lambda get-function-concurrency --function-name "$F"   # ReservedConcurrentExecutions: 1
```

<!-- BEGIN_TF_DOCS -->
## Requirements

| Name | Version |
|------|---------|
| <a name="requirement_terraform"></a> [terraform](#requirement\_terraform) | >= 1.3, < 2.0 |
| <a name="requirement_aws"></a> [aws](#requirement\_aws) | >= 6.0, < 7.0 |
| <a name="requirement_vault"></a> [vault](#requirement\_vault) | >= 5.0, < 6.0 |

## Providers

| Name | Version |
|------|---------|
| <a name="provider_aws"></a> [aws](#provider\_aws) | 6.68.0 |
| <a name="provider_vault"></a> [vault](#provider\_vault) | 5.12.0 |

## Modules

| Name | Source | Version |
|------|--------|---------|
| <a name="module_credentials"></a> [credentials](#module\_credentials) | git@github.com:companieshouse/terraform-modules//aws/parameter-store | 1.0.442 |
| <a name="module_iac_tags"></a> [iac\_tags](#module\_iac\_tags) | git@github.com:companieshouse/terraform-modules//aws/tagging/iac | 1.0.442 |
| <a name="module_lambda"></a> [lambda](#module\_lambda) | git@github.com:companieshouse/terraform-modules//aws/lambda | 1.0.442 |
| <a name="module_owner_tags"></a> [owner\_tags](#module\_owner\_tags) | git@github.com:companieshouse/terraform-modules//aws/tagging/owner | 1.0.442 |

## Resources

| Name | Type |
|------|------|
| [aws_cloudwatch_metric_alarm.errors](https://registry.terraform.io/providers/hashicorp/aws/latest/docs/resources/cloudwatch_metric_alarm) | resource |
| [aws_security_group.ssm_endpoint](https://registry.terraform.io/providers/hashicorp/aws/latest/docs/resources/security_group) | resource |
| [aws_vpc_endpoint.ssm](https://registry.terraform.io/providers/hashicorp/aws/latest/docs/resources/vpc_endpoint) | resource |
| [aws_vpc_security_group_egress_rule.lambda_to_aurora](https://registry.terraform.io/providers/hashicorp/aws/latest/docs/resources/vpc_security_group_egress_rule) | resource |
| [aws_vpc_security_group_egress_rule.lambda_to_s3](https://registry.terraform.io/providers/hashicorp/aws/latest/docs/resources/vpc_security_group_egress_rule) | resource |
| [aws_vpc_security_group_egress_rule.lambda_to_ssm_endpoint](https://registry.terraform.io/providers/hashicorp/aws/latest/docs/resources/vpc_security_group_egress_rule) | resource |
| [aws_vpc_security_group_ingress_rule.aurora_from_lambda](https://registry.terraform.io/providers/hashicorp/aws/latest/docs/resources/vpc_security_group_ingress_rule) | resource |
| [aws_vpc_security_group_ingress_rule.ssm_endpoint_from_lambda](https://registry.terraform.io/providers/hashicorp/aws/latest/docs/resources/vpc_security_group_ingress_rule) | resource |
| [aws_caller_identity.current](https://registry.terraform.io/providers/hashicorp/aws/latest/docs/data-sources/caller_identity) | data source |
| [aws_iam_policy_document.db_migrate](https://registry.terraform.io/providers/hashicorp/aws/latest/docs/data-sources/iam_policy_document) | data source |
| [aws_iam_policy_document.ssm_endpoint](https://registry.terraform.io/providers/hashicorp/aws/latest/docs/data-sources/iam_policy_document) | data source |
| [aws_iam_user.concourse](https://registry.terraform.io/providers/hashicorp/aws/latest/docs/data-sources/iam_user) | data source |
| [aws_kms_key.parameters](https://registry.terraform.io/providers/hashicorp/aws/latest/docs/data-sources/kms_key) | data source |
| [aws_rds_cluster.aurora](https://registry.terraform.io/providers/hashicorp/aws/latest/docs/data-sources/rds_cluster) | data source |
| [aws_security_group.aurora](https://registry.terraform.io/providers/hashicorp/aws/latest/docs/data-sources/security_group) | data source |
| [aws_sns_topic.alarms](https://registry.terraform.io/providers/hashicorp/aws/latest/docs/data-sources/sns_topic) | data source |
| [aws_subnets.data](https://registry.terraform.io/providers/hashicorp/aws/latest/docs/data-sources/subnets) | data source |
| [aws_vpc.vpc](https://registry.terraform.io/providers/hashicorp/aws/latest/docs/data-sources/vpc) | data source |
| [aws_vpc_endpoint.s3](https://registry.terraform.io/providers/hashicorp/aws/latest/docs/data-sources/vpc_endpoint) | data source |
| [vault_generic_secret.database_secrets](https://registry.terraform.io/providers/hashicorp/vault/latest/docs/data-sources/generic_secret) | data source |
| [vault_generic_secret.stack_secrets](https://registry.terraform.io/providers/hashicorp/vault/latest/docs/data-sources/generic_secret) | data source |

## Inputs

| Name | Description | Type | Default | Required |
|------|-------------|------|---------|:--------:|
| <a name="input_alarm_topic_name"></a> [alarm\_topic\_name](#input\_alarm\_topic\_name) | The SNS topic the Errors alarm notifies. Defaults to the stack's common-services-<environment>-cloudwatch-alarms-notify-topic | `string` | `null` | no |
| <a name="input_aws_profile"></a> [aws\_profile](#input\_aws\_profile) | The AWS profile name; used as a prefix for Vault secrets | `string` | n/a | yes |
| <a name="input_aws_region"></a> [aws\_region](#input\_aws\_region) | The AWS region in which resources will be created | `string` | `"eu-west-2"` | no |
| <a name="input_concourse_iam_user_name"></a> [concourse\_iam\_user\_name](#input\_concourse\_iam\_user\_name) | The IAM user whose access keys the environment's Concourse jobs use; the only principal allowed to invoke the function | `string` | `"concourse-platform"` | no |
| <a name="input_environment"></a> [environment](#input\_environment) | The environment name to be used when creating AWS resources | `string` | n/a | yes |
| <a name="input_hashicorp_vault_password"></a> [hashicorp\_vault\_password](#input\_hashicorp\_vault\_password) | The password used when retrieving configuration from Hashicorp Vault | `string` | n/a | yes |
| <a name="input_hashicorp_vault_username"></a> [hashicorp\_vault\_username](#input\_hashicorp\_vault\_username) | The username used when retrieving configuration from Hashicorp Vault | `string` | n/a | yes |
| <a name="input_lambda_handler_name"></a> [lambda\_handler\_name](#input\_lambda\_handler\_name) | The Lambda function entrypoint | `string` | `"uk.gov.companieshouse.addresslookup.dbmigrate.Handler::handleRequest"` | no |
| <a name="input_lambda_logs_retention_days"></a> [lambda\_logs\_retention\_days](#input\_lambda\_logs\_retention\_days) | The number of days to retain the function's logs in CloudWatch | `number` | `90` | no |
| <a name="input_lambda_memory_size"></a> [lambda\_memory\_size](#input\_lambda\_memory\_size) | The amount of memory made available to the Lambda function at runtime in megabytes | `number` | `1024` | no |
| <a name="input_lambda_runtime"></a> [lambda\_runtime](#input\_lambda\_runtime) | The Lambda managed runtime | `string` | `"java21"` | no |
| <a name="input_lambda_timeout_seconds"></a> [lambda\_timeout\_seconds](#input\_lambda\_timeout\_seconds) | The amount of time the Lambda function is allowed to run before being stopped. Concourse invokes it with --cli-read-timeout 900 | `number` | `900` | no |
| <a name="input_lock_timeout"></a> [lock\_timeout](#input\_lock\_timeout) | PostgreSQL lock\_timeout for each migration (LOCK\_TIMEOUT) | `string` | `"10s"` | no |
| <a name="input_release_artifact_key"></a> [release\_artifact\_key](#input\_release\_artifact\_key) | Key of the Lambda code object (address-lookup-db-migrate-<version>.zip) in the release bucket | `string` | n/a | yes |
| <a name="input_release_bucket_name"></a> [release\_bucket\_name](#input\_release\_bucket\_name) | The name of the S3 bucket containing the Lambda release artefact | `string` | n/a | yes |
| <a name="input_snapshot_bucket_name"></a> [snapshot\_bucket\_name](#input\_snapshot\_bucket\_name) | The versioned bucket the Concourse gather-snapshot job writes Flyway snapshots to (SNAPSHOT\_BUCKET) | `string` | n/a | yes |
| <a name="input_snapshot_kms_key_arn"></a> [snapshot\_kms\_key\_arn](#input\_snapshot\_kms\_key\_arn) | The KMS key the snapshot bucket encrypts with, if it uses SSE-KMS. Empty for SSE-S3 | `string` | `""` | no |
| <a name="input_snapshot_prefix"></a> [snapshot\_prefix](#input\_snapshot\_prefix) | The key prefix of the snapshots in the snapshot bucket; the function can read nothing else | `string` | `"snapshots"` | no |

## Outputs

| Name | Description |
|------|-------------|
| <a name="output_credentials_parameter_name"></a> [credentials\_parameter\_name](#output\_credentials\_parameter\_name) | The SecureString holding the schema owner's username, password and jdbcUrl |
| <a name="output_lambda_function_name"></a> [lambda\_function\_name](#output\_lambda\_function\_name) | The function the environment's Concourse update, repair and API check-db-schema jobs invoke |
| <a name="output_lambda_role_arn"></a> [lambda\_role\_arn](#output\_lambda\_role\_arn) | The function's execution role |
| <a name="output_security_group_id"></a> [security\_group\_id](#output\_security\_group\_id) | The function's security group; Aurora admits it on the database port |
| <a name="output_ssm_endpoint_dns_name"></a> [ssm\_endpoint\_dns\_name](#output\_ssm\_endpoint\_dns\_name) | The SSM interface endpoint the function uses (private DNS is off) |
<!-- END_TF_DOCS -->
