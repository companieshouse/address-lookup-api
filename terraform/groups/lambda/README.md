# terraform/groups/lambda

Deploys the address-lookup Lambda functions. Today that is only the schema
migrator, `address-lookup-schema-migrator-<environment>`. It applies a
released `db-schema` changelog to Aurora with Liquibase, and it is the only
thing allowed to change the Aurora schema. The end-to-end design, pipeline and
runbook are in [`db-schema/README.md`](../../../db-schema/README.md).

```
  merge --> Concourse --> release bucket (address-lookup-db-schema-<v>.zip)
                 |
                 +--invoke--> schema migrator --> Parameter Store (credentials, fed from Vault)
                                    |         --> release bucket (changelog)
                                    +--Liquibase--> Aurora (DATABASECHANGELOG is the history)
```

## Schema migrator

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
| Lambda | `terraform-modules//aws/lambda` in `terraform/groups/lambda`, artefact `<name>-<version>.zip` from the release bucket, built as a shaded `-lambda.jar` | `search-service-comparison-utility`, `services-dashboard-api` |
| Parameter Store | `terraform-modules//aws/parameter-store` fed from Vault (not Secrets Manager) | `search-service-comparison-utility`, `ecs-service-secrets-stack` |
| KMS | `terraform-modules//aws/kms` | `identity-verification-api`, `notifications-service-stack` |
| Vault | `vault-providers/` with `vault.tf` linked to `userpass`, as in the `aurora` and `ecs-service` groups | this repository |

## Deployment

Applied by the `address-lookup-api` pipeline in `companieshouse/ci-pipelines`
(`cidev-lambda-plan` / `cidev-lambda-apply`, `GROUP: lambda`), in the same way
as the existing `aurora` and `ecs-service` groups. `lambda_version` selects
which `lambda-X.Y.Z` release artefact is deployed.

The migrator reads `master_username` and `master_password` from
`applications/<aws_profile>/<environment>/common-services-stack/address-rds`,
the same secret the `aurora` group creates the cluster with. Nothing further
needs to be added to Vault.

## Prerequisites

* The `aws/lambda` module inputs used by `schema-migrator.tf`
  (`lambda_architectures`, `lambda_reserved_concurrent_executions`,
  `lambda_maximum_retry_attempts`, `lambda_ssm_parameter_arns`) come from the
  `ALS-51/Liquibase-Lambda-Impl` branch of `terraform-modules`. Pin
  `module.schema_migrator` to the tag cut from it. `1.0.434` does not have them.
* The `aurora` group has been applied, so the cluster and its security group exist.


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
| <a name="provider_aws"></a> [aws](#provider\_aws) | >= 6.0, < 7.0 |
| <a name="provider_vault"></a> [vault](#provider\_vault) | >= 5.0, < 6.0 |

## Modules

| Name | Source | Version |
|------|--------|---------|
| <a name="module_iac_tags"></a> [iac\_tags](#module\_iac\_tags) | git@github.com:companieshouse/terraform-modules//aws/tagging/iac | 1.0.434 |
| <a name="module_owner_tags"></a> [owner\_tags](#module\_owner\_tags) | git@github.com:companieshouse/terraform-modules//aws/tagging/owner | 1.0.434 |
| <a name="module_schema_migrator"></a> [schema\_migrator](#module\_schema\_migrator) | git@github.com:companieshouse/terraform-modules.git//aws/lambda | 1.0.435 |
| <a name="module_schema_migrator_kms"></a> [schema\_migrator\_kms](#module\_schema\_migrator\_kms) | git@github.com:companieshouse/terraform-modules//aws/kms | 1.0.434 |
| <a name="module_schema_migrator_secrets"></a> [schema\_migrator\_secrets](#module\_schema\_migrator\_secrets) | git@github.com:companieshouse/terraform-modules//aws/parameter-store | 1.0.434 |

## Resources

| Name | Type |
|------|------|
| [aws_vpc_security_group_egress_rule.schema_migrator_to_aurora](https://registry.terraform.io/providers/hashicorp/aws/latest/docs/resources/vpc_security_group_egress_rule) | resource |
| [aws_vpc_security_group_ingress_rule.schema_migrator_to_aurora](https://registry.terraform.io/providers/hashicorp/aws/latest/docs/resources/vpc_security_group_ingress_rule) | resource |
| [aws_caller_identity.aws_identity](https://registry.terraform.io/providers/hashicorp/aws/latest/docs/data-sources/caller_identity) | data source |
| [aws_iam_policy_document.schema_migrator](https://registry.terraform.io/providers/hashicorp/aws/latest/docs/data-sources/iam_policy_document) | data source |
| [aws_partition.current](https://registry.terraform.io/providers/hashicorp/aws/latest/docs/data-sources/partition) | data source |
| [aws_rds_cluster.aurora](https://registry.terraform.io/providers/hashicorp/aws/latest/docs/data-sources/rds_cluster) | data source |
| [aws_security_group.aurora](https://registry.terraform.io/providers/hashicorp/aws/latest/docs/data-sources/security_group) | data source |
| [aws_subnets.application](https://registry.terraform.io/providers/hashicorp/aws/latest/docs/data-sources/subnets) | data source |
| [aws_vpc.vpc](https://registry.terraform.io/providers/hashicorp/aws/latest/docs/data-sources/vpc) | data source |
| [vault_generic_secret.database_secrets](https://registry.terraform.io/providers/hashicorp/vault/latest/docs/data-sources/generic_secret) | data source |
| [vault_generic_secret.stack_secrets](https://registry.terraform.io/providers/hashicorp/vault/latest/docs/data-sources/generic_secret) | data source |

## Inputs

| Name | Description | Type | Default | Required |
|------|-------------|------|---------|:--------:|
| <a name="input_aws_profile"></a> [aws\_profile](#input\_aws\_profile) | The AWS profile to use for authentication, defined in environments vars. | `string` | n/a | yes |
| <a name="input_aws_region"></a> [aws\_region](#input\_aws\_region) | The region in which to provision resources | `string` | `"eu-west-2"` | no |
| <a name="input_environment"></a> [environment](#input\_environment) | The environment name, defined in environments vars. | `string` | n/a | yes |
| <a name="input_hashicorp_vault_password"></a> [hashicorp\_vault\_password](#input\_hashicorp\_vault\_password) | The password used when retrieving configuration from Hashicorp Vault | `string` | n/a | yes |
| <a name="input_hashicorp_vault_username"></a> [hashicorp\_vault\_username](#input\_hashicorp\_vault\_username) | The username used when retrieving configuration from Hashicorp Vault | `string` | n/a | yes |
| <a name="input_lambda_artifact_key_prefix"></a> [lambda\_artifact\_key\_prefix](#input\_lambda\_artifact\_key\_prefix) | The prefix under which Lambda and db-schema release artefacts are published in the release bucket | `string` | `"address-lookup-api"` | no |
| <a name="input_lambda_logs_retention_days"></a> [lambda\_logs\_retention\_days](#input\_lambda\_logs\_retention\_days) | The number of days to retain Lambda logs in CloudWatch | `number` | `90` | no |
| <a name="input_lambda_runtime"></a> [lambda\_runtime](#input\_lambda\_runtime) | The Lambda runtime to use for all functions | `string` | `"java21"` | no |
| <a name="input_lambda_version"></a> [lambda\_version](#input\_lambda\_version) | The version of the address-lookup Lambda artefacts to deploy, from the lambda-X.Y.Z release stream | `string` | n/a | yes |
| <a name="input_release_bucket_name"></a> [release\_bucket\_name](#input\_release\_bucket\_name) | The name of the S3 bucket containing the release artefacts for the Lambda functions and the db-schema changelogs | `string` | n/a | yes |

## Outputs

| Name | Description |
|------|-------------|
| <a name="output_schema_migrator_function_name"></a> [schema\_migrator\_function\_name](#output\_schema\_migrator\_function\_name) | The function Concourse invokes to apply a released db-schema changelog to Aurora |
<!-- END_TF_DOCS -->