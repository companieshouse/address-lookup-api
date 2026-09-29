# terraform/groups/liquibase-lambda

Deploys the address-lookup Liquibase Lambda, `address-lookup-liquibase-lambda-<environment>`.
It applies a released changelog from
`address-lookup-api/src/main/resources/db/changelog` to Aurora, and it is the
only thing allowed to change the Aurora schema. The end-to-end design, pipeline
and runbook are in [`docs/database-changes.md`](../../../docs/database-changes.md).

```
  merge --> Concourse --write--> release bucket (address-lookup-db-schema-<v>.zip)
                 |
                 +--invoke--> Liquibase Lambda --read--> Parameter Store (credentials, fed from Vault)
                                    |         --read--> release bucket (changelog)
                                    +--Liquibase--> Aurora (DATABASECHANGELOG is the history)
```

The group follows the layout of `services-dashboard-api/terraform/groups/lambda`:
`main.tf` (providers, `module "secrets"`, `module "lambda"`), `data.tf`,
`locals.tf`, `variables.tf` (mandatory then optional), `vault.tf` and
`profiles/<aws_profile>/<environment>/vars`. The pipeline passes
`release_bucket_name` and `release_artifact_key`.

## What is created

| Concern | How it is handled here |
| ------- | ---------------------- |
| Trigger | No event rules and no resource policy. Concourse invokes it synchronously after a `db-schema` release |
| Changelog | `s3:GetObject` on `address-lookup-api/address-lookup-db-schema-*.zip` in the release bucket only. The function also checks the SHA-256 that the pipeline passes in |
| Credentials | Aurora master credentials from Vault (`common-services-stack/address-rds`), copied by `terraform-modules//aws/parameter-store` into two SecureStrings under `/address-lookup-liquibase-lambda-<environment>/`, encrypted with `alias/aws/ssm`. The function reads them at invocation time; `lambda_ssm_parameter_arns` limits it to those two parameters |
| Network | Egress on 443 (S3, Parameter Store) and on the database port to the Aurora security group only. Aurora gets a matching ingress rule |
| Concurrency | Reserved concurrency of 1 and no retries. One migration at a time; Concourse decides what to do after a failure |
| Timeout | 900 seconds. `UPDATE` stops `MIN_REMAINING_MILLIS` before that and returns `PARTIAL`, so the Liquibase lock is always released |

It uses the master user because `CREATE EXTENSION postgis` needs
`rds_superuser`. A dedicated migration role with that grant is a follow-up.

## Deployment

Applied by the `address-lookup-api` pipeline in `companieshouse/ci-pipelines`
(`cidev-liquibase-lambda-plan` / `cidev-liquibase-lambda-apply`, `GROUP: liquibase-lambda`), in the
same way as the `aurora` and `ecs-service` groups, with
`release_artifact_key=address-lookup-api/address-lookup-liquibase-lambda-<X.Y.Z>.zip`.

Nothing needs to be added to Vault: the function uses the secret the `aurora`
group creates the cluster with.

## Prerequisites

* `module "lambda"` uses inputs (`lambda_architectures`,
  `lambda_reserved_concurrent_executions`, `lambda_maximum_retry_attempts`,
  `lambda_ssm_parameter_arns`, the `security_group_id` output) from the
  `ALS-51/Liquibase-Lamba-Support` branch of `terraform-modules`. Pin it to the
  tag cut when that branch merges; `1.0.434`–`1.0.436` do not have them.
* The `aurora` group has been applied, so the cluster and its security group exist.

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
| <a name="module_iac_tags"></a> [iac\_tags](#module\_iac\_tags) | git@github.com:companieshouse/terraform-modules//aws/tagging/iac | 1.0.434 |
| <a name="module_lambda"></a> [lambda](#module\_lambda) | git@github.com:companieshouse/terraform-modules.git//aws/lambda | ALS-51/Liquibase-Lamba-Support |
| <a name="module_owner_tags"></a> [owner\_tags](#module\_owner\_tags) | git@github.com:companieshouse/terraform-modules//aws/tagging/owner | 1.0.434 |
| <a name="module_secrets"></a> [secrets](#module\_secrets) | git@github.com:companieshouse/terraform-modules//aws/parameter-store | 1.0.434 |

## Resources

| Name | Type |
| ---- | ---- |
| [aws_vpc_security_group_egress_rule.lambda_to_aurora](https://registry.terraform.io/providers/hashicorp/aws/latest/docs/resources/vpc_security_group_egress_rule) | resource |
| [aws_vpc_security_group_ingress_rule.aurora_from_lambda](https://registry.terraform.io/providers/hashicorp/aws/latest/docs/resources/vpc_security_group_ingress_rule) | resource |
| [aws_caller_identity.aws_identity](https://registry.terraform.io/providers/hashicorp/aws/latest/docs/data-sources/caller_identity) | data source |
| [aws_iam_policy_document.read_changelogs](https://registry.terraform.io/providers/hashicorp/aws/latest/docs/data-sources/iam_policy_document) | data source |
| [aws_kms_key.kms_key](https://registry.terraform.io/providers/hashicorp/aws/latest/docs/data-sources/kms_key) | data source |
| [aws_rds_cluster.aurora](https://registry.terraform.io/providers/hashicorp/aws/latest/docs/data-sources/rds_cluster) | data source |
| [aws_security_group.aurora](https://registry.terraform.io/providers/hashicorp/aws/latest/docs/data-sources/security_group) | data source |
| [aws_subnets.application](https://registry.terraform.io/providers/hashicorp/aws/latest/docs/data-sources/subnets) | data source |
| [aws_vpc.vpc](https://registry.terraform.io/providers/hashicorp/aws/latest/docs/data-sources/vpc) | data source |
| [vault_generic_secret.database_secrets](https://registry.terraform.io/providers/hashicorp/vault/latest/docs/data-sources/generic_secret) | data source |
| [vault_generic_secret.stack_secrets](https://registry.terraform.io/providers/hashicorp/vault/latest/docs/data-sources/generic_secret) | data source |

## Inputs

| Name | Description | Type | Default | Required |
| ---- | ----------- | ---- | ------- | :------: |
| <a name="input_aws_account"></a> [aws\_account](#input\_aws\_account) | The AWS account name | `string` | `"development"` | no |
| <a name="input_aws_profile"></a> [aws\_profile](#input\_aws\_profile) | The AWS profile name; used as a prefix for Vault secrets | `string` | n/a | yes |
| <a name="input_aws_region"></a> [aws\_region](#input\_aws\_region) | The AWS region in which resources will be created | `string` | `"eu-west-2"` | no |
| <a name="input_environment"></a> [environment](#input\_environment) | The environment name to be used when creating AWS resources | `string` | n/a | yes |
| <a name="input_hashicorp_vault_password"></a> [hashicorp\_vault\_password](#input\_hashicorp\_vault\_password) | The password used when retrieving configuration from Hashicorp Vault | `string` | n/a | yes |
| <a name="input_hashicorp_vault_username"></a> [hashicorp\_vault\_username](#input\_hashicorp\_vault\_username) | The username used when retrieving configuration from Hashicorp Vault | `string` | n/a | yes |
| <a name="input_lambda_architectures"></a> [lambda\_architectures](#input\_lambda\_architectures) | The instruction set architecture for the Lambda function | `list(string)` | <pre>[<br/>  "arm64"<br/>]</pre> | no |
| <a name="input_lambda_handler_name"></a> [lambda\_handler\_name](#input\_lambda\_handler\_name) | The lambda function entrypoint | `string` | `"uk.gov.companieshouse.addresslookup.lambda.Handler::handleRequest"` | no |
| <a name="input_lambda_logs_retention_days"></a> [lambda\_logs\_retention\_days](#input\_lambda\_logs\_retention\_days) | The number of days to retain Lambda logs in CloudWatch | `number` | `90` | no |
| <a name="input_lambda_memory_size"></a> [lambda\_memory\_size](#input\_lambda\_memory\_size) | The amount of memory made available to the Lambda function at runtime in megabytes | `number` | `1024` | no |
| <a name="input_lambda_runtime"></a> [lambda\_runtime](#input\_lambda\_runtime) | The lambda runtime to use for the function | `string` | `"java21"` | no |
| <a name="input_lambda_timeout_seconds"></a> [lambda\_timeout\_seconds](#input\_lambda\_timeout\_seconds) | The amount of time the lambda function is allowed to run before being stopped. Keep MIN\_REMAINING\_MILLIS and STATEMENT\_TIMEOUT\_MILLIS below it | `number` | `900` | no |
| <a name="input_release_artifact_key"></a> [release\_artifact\_key](#input\_release\_artifact\_key) | Key of the Lambda code object in the S3 bucket | `string` | n/a | yes |
| <a name="input_release_bucket_name"></a> [release\_bucket\_name](#input\_release\_bucket\_name) | The name of the S3 bucket containing the release artifact for the Lambda function and the released db-schema changelogs | `string` | n/a | yes |

## Outputs

| Name | Description |
| ---- | ----------- |
| <a name="output_lambda_function_name"></a> [lambda\_function\_name](#output\_lambda\_function\_name) | The function Concourse invokes to apply a released db-schema changelog to Aurora |
<!-- END_TF_DOCS -->
