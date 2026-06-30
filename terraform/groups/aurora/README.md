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
| <a name="provider_aws"></a> [aws](#provider\_aws) | 6.52.0 |
| <a name="provider_vault"></a> [vault](#provider\_vault) | 5.10.1 |

## Modules

| Name | Source | Version |
|------|--------|---------|
| <a name="module_aurora_postgres"></a> [aurora\_postgres](#module\_aurora\_postgres) | git@github.com:companieshouse/terraform-modules//aws/aurora | 1.0.397 |
| <a name="module_iac_tags"></a> [iac\_tags](#module\_iac\_tags) | git@github.com:companieshouse/terraform-modules//aws/tagging/iac | 1.0.397 |
| <a name="module_owner_tags"></a> [owner\_tags](#module\_owner\_tags) | git@github.com:companieshouse/terraform-modules//aws/tagging/owner | 1.0.397 |

## Resources

| Name | Type |
|------|------|
| [aws_subnets.data](https://registry.terraform.io/providers/hashicorp/aws/latest/docs/data-sources/subnets) | data source |
| [aws_vpc.vpc](https://registry.terraform.io/providers/hashicorp/aws/latest/docs/data-sources/vpc) | data source |
| [vault_generic_secret.aurora](https://registry.terraform.io/providers/hashicorp/vault/latest/docs/data-sources/generic_secret) | data source |
| [vault_generic_secret.stack_secrets](https://registry.terraform.io/providers/hashicorp/vault/latest/docs/data-sources/generic_secret) | data source |

## Inputs

| Name | Description | Type | Default | Required |
|------|-------------|------|---------|:--------:|
| <a name="input_aws_profile"></a> [aws\_profile](#input\_aws\_profile) | The AWS profile to use for authentication, defined in environments vars. | `string` | n/a | yes |
| <a name="input_aws_region"></a> [aws\_region](#input\_aws\_region) | The region in which to provision resources | `string` | `"eu-west-2"` | no |
| <a name="input_environment"></a> [environment](#input\_environment) | The environment name, defined in environments vars. | `string` | n/a | yes |
| <a name="input_hashicorp_vault_password"></a> [hashicorp\_vault\_password](#input\_hashicorp\_vault\_password) | The password used when retrieving configuration from Hashicorp Vault | `string` | n/a | yes |
| <a name="input_hashicorp_vault_username"></a> [hashicorp\_vault\_username](#input\_hashicorp\_vault\_username) | The username used when retrieving configuration from Hashicorp Vault | `string` | n/a | yes |
| <a name="input_instances"></a> [instances](#input\_instances) | Map of instance configurations for the Aurora cluster | `map(object({}))` | <pre>{<br/>  "primary": {}<br/>}</pre> | no |

## Outputs

No outputs.
<!-- END_TF_DOCS -->