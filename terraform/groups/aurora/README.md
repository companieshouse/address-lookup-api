# terraform/groups/aurora

The address lookup Aurora PostgreSQL cluster, and everything the database needs before the first
Flyway run (ALS-90): PostGIS, the schema-owner role, TLS 1.3 only, and the credentials both
migration Lambdas read from Parameter Store.

## What it creates

| What | Details |
|------|---------|
| Aurora cluster | `<env>-address-rds`, database `addressdb`, terraform-modules `aws/aurora`. |
| TLS | Cluster parameter group: `rds.force_ssl=1`, `ssl_min_protocol_version=TLSv1.3` (the agreed post-quantum readiness step). The master jdbcUrl uses `sslmode=verify-full` to verify the server certificate and hostname before authentication. The schema-owner jdbcUrl remains `sslmode=require`. The cluster refuses non-TLS and pre-1.3 connections. |
| Credentials | SecureStrings `/address-lookup/<env>/db/master` and `/address-lookup/<env>/db/schema-owner` = `{"username","password","jdbcUrl"}` (writer endpoint), written from Vault by `module.credentials` and encrypted with `alias/<aws_profile>/environment-services-kms`. |
| SSM endpoint | Interface endpoint `address-lookup-<env>-ssm` (private DNS off) and its security group `address-lookup-<env>-ssm-endpoint`. Its policy lets the bootstrap Lambda read both parameters and the db-migrate Lambda's role read only the schema owner's. `terraform/groups/db-migrate` looks both up by Name tag. |
| db-bootstrap Lambda | `address-lookup-db-bootstrap-<env>` (code: `lambdas/db-bootstrap`). No CIDR egress: only Aurora (5432) and the SSM endpoint (443). No event sources or invoke permissions. |
| Bootstrap run | `aws_lambda_invocation.db_bootstrap` runs `{"command":"bootstrap"}` on the first apply and whenever the release, the cluster, the schema owner's credentials or the TLS settings change. A failure fails the apply. |

### What the bootstrap does

Connected as the master user (the only code that uses it):

1. `CREATE EXTENSION IF NOT EXISTS postgis`, as `rds_superuser`, so the V2026.001 baseline's own
   `CREATE EXTENSION IF NOT EXISTS postgis` is a no-op.
2. Creates the schema-owner role (or, on a re-run, updates it): `LOGIN`, password set with a client-side
   SCRAM-SHA-256 hash so the plaintext never reaches the server's logs, `CONNECT, CREATE` on the database
   (to create the six schemas) and `USAGE, CREATE` on schema `public` (PostgreSQL 15+ no longer grants
   that to everyone; Flyway keeps `flyway_schema_history` there unless told otherwise). Not a superuser,
   not in `rds_superuser`, not the master user.
3. Verifies, and fails the invocation if anything is wrong:
   * `postgis` is in `pg_extension`
   * the owner exists, can log in, isn't a superuser or in `rds_superuser`, isn't the master user, and has the grants above
   * the database is **EMPTY** (no schemas or relations except PostGIS's own) or **MIGRATED**
     (`flyway_schema_history` exists). **NOT_EMPTY** fails with the offending objects listed, because Flyway
     would stop with a "non-empty schema" error
   * `rds.force_ssl` is `1`, `ssl_min_protocol_version` is `TLSv1.3`, and its own connection uses TLS 1.3
   * a client limited to TLS 1.2 is refused and one limited to TLS 1.3 is accepted (PostgreSQL SSLRequest
     then a handshake pinned to one version, stopped at the server certificate so it needs no CA file; no credentials are sent).
     These probes check protocol support only, not endpoint identity; JDBC independently verifies the certificate and hostname.

It never creates application schemas or tables: those all come from Flyway, starting at V2026.001.

## Local tests

Run `make test-db-bootstrap` for the Lambda's unit tests; no Docker or database is required.
Run `make test-db-bootstrap-integration` separately for the PostgreSQL/PostGIS and Flyway
integration test, which requires Docker.

## Prerequisites

* **Vault**, at `applications/<aws_profile>/<env>/common-services-stack/address-rds`, next to
  `master_username`/`master_password`:

  ```sh
  vault kv patch applications/<aws_profile>/<env>/common-services-stack/address-rds \
    schema_owner_username=address_lookup_owner \
    schema_owner_password="$(openssl rand -base64 33 | tr -d '/+=' | cut -c1-40)"
  ```

* **The db-bootstrap release** `address-lookup-db-bootstrap-<version>.zip` in the release bucket
  (Concourse `db-bootstrap-lambda-release` job, from a `db-bootstrap-lambda-X.Y.Z` tag).
* **An approved RDS CA bundle available inside the bootstrap Lambda.** PostgreSQL JDBC defaults to
  `${user.home}/.postgresql/root.crt`; alternatively, the master jdbcUrl can specify
  `sslrootcert=/absolute/path/to/approved-bundle.pem`. To use an existing JVM truststore instead,
  add `sslfactory=org.postgresql.ssl.DefaultJavaSSLFactory` to the master jdbcUrl and configure the
  JVM's truststore through `JAVA_TOOL_OPTIONS`. The deployment must provision the chosen trust
  material before invoking the Lambda. It is not downloaded at runtime. Missing or untrusted certificates fail the
  connection, including when `VERIFY_TLS=false`; that flag skips only the later verification checks.
  Only the standard `LibPQFactory` and `DefaultJavaSSLFactory` implementations are allowed;
  `sslfactoryarg` and `sslhostnameverifier` URL options are rejected.
* **An empty database.** Only `postgis` may be installed beforehand. If the bootstrap reports
  NOT_EMPTY, remove the listed objects (no environment holds address lookup objects yet) and apply again.

## Deployment

The pipeline's aurora plan/apply jobs pass `release_bucket_name` and `db_bootstrap_release_artifact_key`.
Apply this group **before** `terraform/groups/db-migrate`, which looks up the SSM endpoint and the
schema-owner parameter created here.

Note: this branch is based on ALS-81, so the first apply also upgrades the engine to 18.6.

## Rotation

Change `schema_owner_password` in Vault, then plan/apply this group. The parameter is overwritten, the
bootstrap re-runs (its trigger is the password's SHA-256) and sets the new password on the role. Both
Lambdas read the parameter on every invocation, so nothing is redeployed; the next `<env>-db-schema-migrate` or
`info` uses the new password. Rotating the master password in Vault is applied by the aurora module
and the master parameter in the same apply.

## Verifying the acceptance criteria

```sh
ENV=cidev
FN=address-lookup-db-bootstrap-$ENV

# The bootstrap's report from the last apply, or a fresh read-only check:
terraform output -json db_bootstrap_result
aws lambda invoke --function-name "$FN" --cli-binary-format raw-in-base64-out \
  --payload '{"command":"verify"}' /dev/stdout
#   databaseState EMPTY before the first Flyway run, MIGRATED after; postgisVersion set;
#   schemaOwner != master; forceSsl "1"; sslMinProtocolVersion "TLSv1.3"; tls12 REFUSED; tls13 ACCEPTED

# Parameters are SecureStrings with the customer-managed key:
aws ssm describe-parameters --parameter-filters "Key=Name,Option=BeginsWith,Values=/address-lookup/$ENV/db/" \
  --query 'Parameters[].[Name,Type,KeyId]' --output table
```

From a host that can reach the cluster (OpenSSL 1.1.1+; macOS LibreSSL has no `-starttls postgres`):

```sh
HOST=<writer endpoint>
openssl s_client -connect "$HOST:5432" -starttls postgres -tls1_2 </dev/null  # alert protocol version: refused
openssl s_client -connect "$HOST:5432" -starttls postgres -tls1_3 </dev/null  # Protocol: TLSv1.3

# As the master or schema owner:
psql "host=$HOST dbname=addressdb sslmode=require" -c "SELECT extname FROM pg_extension"
psql "host=$HOST dbname=addressdb sslmode=require ssl_max_protocol_version=TLSv1.2" -c 'SELECT 1'  # refused
```

After the first `<env>-db-schema-migrate`, Flyway's `info` shows V2026.001 to V2026.004 applied with no
"non-empty schema" error.

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
| <a name="module_credentials"></a> [credentials](#module\_credentials) | git@github.com:companieshouse/terraform-modules//aws/parameter-store | 1.0.442 |
| <a name="module_db_bootstrap"></a> [db\_bootstrap](#module\_db\_bootstrap) | git@github.com:companieshouse/terraform-modules//aws/lambda | 1.0.442 |
| <a name="module_iac_tags"></a> [iac\_tags](#module\_iac\_tags) | git@github.com:companieshouse/terraform-modules//aws/tagging/iac | 1.0.397 |
| <a name="module_owner_tags"></a> [owner\_tags](#module\_owner\_tags) | git@github.com:companieshouse/terraform-modules//aws/tagging/owner | 1.0.397 |

## Resources

| Name | Type |
|------|------|
| [aws_lambda_invocation.db_bootstrap](https://registry.terraform.io/providers/hashicorp/aws/latest/docs/resources/lambda_invocation) | resource |
| [aws_security_group.ssm_endpoint](https://registry.terraform.io/providers/hashicorp/aws/latest/docs/resources/security_group) | resource |
| [aws_vpc_endpoint.ssm](https://registry.terraform.io/providers/hashicorp/aws/latest/docs/resources/vpc_endpoint) | resource |
| [aws_vpc_security_group_egress_rule.db_bootstrap_to_aurora](https://registry.terraform.io/providers/hashicorp/aws/latest/docs/resources/vpc_security_group_egress_rule) | resource |
| [aws_vpc_security_group_egress_rule.db_bootstrap_to_ssm_endpoint](https://registry.terraform.io/providers/hashicorp/aws/latest/docs/resources/vpc_security_group_egress_rule) | resource |
| [aws_vpc_security_group_ingress_rule.aurora_from_db_bootstrap](https://registry.terraform.io/providers/hashicorp/aws/latest/docs/resources/vpc_security_group_ingress_rule) | resource |
| [aws_vpc_security_group_ingress_rule.ssm_endpoint_from_db_bootstrap](https://registry.terraform.io/providers/hashicorp/aws/latest/docs/resources/vpc_security_group_ingress_rule) | resource |
| [aws_caller_identity.current](https://registry.terraform.io/providers/hashicorp/aws/latest/docs/data-sources/caller_identity) | data source |
| [aws_iam_policy_document.db_bootstrap](https://registry.terraform.io/providers/hashicorp/aws/latest/docs/data-sources/iam_policy_document) | data source |
| [aws_iam_policy_document.ssm_endpoint](https://registry.terraform.io/providers/hashicorp/aws/latest/docs/data-sources/iam_policy_document) | data source |
| [aws_kms_key.parameters](https://registry.terraform.io/providers/hashicorp/aws/latest/docs/data-sources/kms_key) | data source |
| [aws_subnets.data](https://registry.terraform.io/providers/hashicorp/aws/latest/docs/data-sources/subnets) | data source |
| [aws_vpc.vpc](https://registry.terraform.io/providers/hashicorp/aws/latest/docs/data-sources/vpc) | data source |
| [vault_generic_secret.aurora](https://registry.terraform.io/providers/hashicorp/vault/latest/docs/data-sources/generic_secret) | data source |
| [vault_generic_secret.stack_secrets](https://registry.terraform.io/providers/hashicorp/vault/latest/docs/data-sources/generic_secret) | data source |

## Inputs

| Name | Description | Type | Default | Required |
|------|-------------|------|---------|:--------:|
| <a name="input_aws_profile"></a> [aws\_profile](#input\_aws\_profile) | The AWS profile to use for authentication, defined in environments vars. | `string` | n/a | yes |
| <a name="input_aws_region"></a> [aws\_region](#input\_aws\_region) | The region in which to provision resources | `string` | `"eu-west-2"` | no |
| <a name="input_db_bootstrap_memory_size"></a> [db\_bootstrap\_memory\_size](#input\_db\_bootstrap\_memory\_size) | Memory, in megabytes, for the db-bootstrap Lambda | `number` | `512` | no |
| <a name="input_db_bootstrap_release_artifact_key"></a> [db\_bootstrap\_release\_artifact\_key](#input\_db\_bootstrap\_release\_artifact\_key) | Key of the db-bootstrap Lambda code (address-lookup-db-bootstrap-<version>.zip) in the release bucket | `string` | n/a | yes |
| <a name="input_db_bootstrap_timeout_seconds"></a> [db\_bootstrap\_timeout\_seconds](#input\_db\_bootstrap\_timeout\_seconds) | Timeout, in seconds, for the db-bootstrap Lambda | `number` | `120` | no |
| <a name="input_db_migrate_function_name"></a> [db\_migrate\_function\_name](#input\_db\_migrate\_function\_name) | function\_name of the db-migrate Lambda (terraform/groups/db-migrate); its role may read the schema-owner parameter through the SSM endpoint | `string` | `"address-lookup-db-migrate"` | no |
| <a name="input_environment"></a> [environment](#input\_environment) | The environment name, defined in environments vars. | `string` | n/a | yes |
| <a name="input_hashicorp_vault_password"></a> [hashicorp\_vault\_password](#input\_hashicorp\_vault\_password) | The password used when retrieving configuration from Hashicorp Vault | `string` | n/a | yes |
| <a name="input_hashicorp_vault_username"></a> [hashicorp\_vault\_username](#input\_hashicorp\_vault\_username) | The username used when retrieving configuration from Hashicorp Vault | `string` | n/a | yes |
| <a name="input_instances"></a> [instances](#input\_instances) | Map of instance configurations for the Aurora cluster | `map(object({}))` | <pre>{<br/>  "primary": {}<br/>}</pre> | no |
| <a name="input_release_bucket_name"></a> [release\_bucket\_name](#input\_release\_bucket\_name) | The S3 bucket holding the db-bootstrap Lambda release artefact | `string` | n/a | yes |

## Outputs

| Name | Description |
|------|-------------|
| <a name="output_cluster_endpoint"></a> [cluster\_endpoint](#output\_cluster\_endpoint) | Writer endpoint for the Aurora PostgreSQL cluster |
| <a name="output_cluster_id"></a> [cluster\_id](#output\_cluster\_id) | Identifier of the Aurora PostgreSQL cluster |
| <a name="output_database_name"></a> [database\_name](#output\_database\_name) | Name of the application database |
| <a name="output_database_port"></a> [database\_port](#output\_database\_port) | Port the Aurora PostgreSQL cluster listens on |
| <a name="output_db_bootstrap_function_name"></a> [db\_bootstrap\_function\_name](#output\_db\_bootstrap\_function\_name) | The db-bootstrap Lambda; invoke with {"command":"verify"} to re-check the database |
| <a name="output_db_bootstrap_result"></a> [db\_bootstrap\_result](#output\_db\_bootstrap\_result) | The last bootstrap's verification report (PostGIS version, owner, database state, TLS checks) |
| <a name="output_master_credentials_parameter_name"></a> [master\_credentials\_parameter\_name](#output\_master\_credentials\_parameter\_name) | SecureString with the master credentials; read only by the db-bootstrap Lambda |
| <a name="output_reader_endpoint"></a> [reader\_endpoint](#output\_reader\_endpoint) | Reader endpoint for the Aurora PostgreSQL cluster |
| <a name="output_schema_owner_credentials_parameter_name"></a> [schema\_owner\_credentials\_parameter\_name](#output\_schema\_owner\_credentials\_parameter\_name) | SecureString with the schema owner's username, password and jdbcUrl; read by the db-bootstrap and db-migrate Lambdas |
| <a name="output_security_group_id"></a> [security\_group\_id](#output\_security\_group\_id) | The Aurora cluster's security group |
| <a name="output_ssm_endpoint_dns_name"></a> [ssm\_endpoint\_dns\_name](#output\_ssm\_endpoint\_dns\_name) | DNS name of the SSM interface endpoint (AWS\_ENDPOINT\_URL\_SSM) |
| <a name="output_ssm_endpoint_id"></a> [ssm\_endpoint\_id](#output\_ssm\_endpoint\_id) | The SSM interface endpoint used by the migration Lambdas (private DNS off) |
| <a name="output_ssm_endpoint_security_group_id"></a> [ssm\_endpoint\_security\_group\_id](#output\_ssm\_endpoint\_security\_group\_id) | The SSM endpoint's security group; terraform/groups/db-migrate adds its ingress rule to it |
<!-- END_TF_DOCS -->