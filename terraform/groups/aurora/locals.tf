locals {
  service_name  = "address-rds"
  database_name = "addressdb"
  stack_name    = "common-services"

  data_subnet_pattern = data.vault_generic_secret.aurora.data["data_subnet_pattern"]
  master_username     = data.vault_generic_secret.aurora.data["master_username"]
  master_password     = data.vault_generic_secret.aurora.data["master_password"]

  # The role that owns the application schemas and runs Flyway (ALS-88/ALS-90). Not the master user.
  schema_owner_username = data.vault_generic_secret.aurora.data["schema_owner_username"]
  schema_owner_password = data.vault_generic_secret.aurora.data["schema_owner_password"]

  vpc_name = data.vault_generic_secret.stack_secrets.data["vpc_name"]

  # TLS: every connection encrypted, TLS 1.3 only (the agreed post-quantum readiness step).
  cluster_parameter_group_settings = [
    {
      name         = "rds.force_ssl"
      value        = "1"
      apply_method = "pending-reboot"
    },
    {
      name         = "rds.allowed_extensions"
      value        = "postgis"
      apply_method = "immediate"
    },
    {
      name         = "ssl_min_protocol_version"
      value        = "TLSv1.3"
      apply_method = "immediate"
    }
  ]

  # terraform-modules aws/lambda names the function <function_name>-<environment> and its role
  # <function_name>-<environment>-lambda-role.
  db_bootstrap_function_name = "address-lookup-db-bootstrap"
  db_bootstrap_lambda_name   = "${local.db_bootstrap_function_name}-${var.environment}"
  db_migrate_role_arn        = "arn:aws:iam::${data.aws_caller_identity.current.account_id}:role/${var.db_migrate_function_name}-${var.environment}-lambda-role"

  database_port = 5432

  # aws/parameter-store names each parameter /<name_prefix>/<key>:
  # /address-lookup/<environment>/db/master and /address-lookup/<environment>/db/schema-owner.
  parameter_prefix            = "address-lookup/${var.environment}/db"
  master_parameter_name       = "/${local.parameter_prefix}/master"
  schema_owner_parameter_name = "/${local.parameter_prefix}/schema-owner"
  parameter_arn_prefix        = "arn:aws:ssm:${var.aws_region}:${data.aws_caller_identity.current.account_id}:parameter"
  master_parameter_arn        = "${local.parameter_arn_prefix}${local.master_parameter_name}"
  schema_owner_parameter_arn  = "${local.parameter_arn_prefix}${local.schema_owner_parameter_name}"
  parameters_kms_alias        = "alias/${var.aws_profile}/environment-services-kms"

  # The writer (cluster) endpoint, never the reader. sslmode=require encrypts every connection without a
  # CA file in the repo or the Lambda zips; the cluster enforces TLS (rds.force_ssl=1, TLS 1.3 minimum).
  jdbc_url = "jdbc:postgresql://${module.aurora_postgres.cluster_endpoint}:${local.database_port}/${local.database_name}?sslmode=require"

  credentials = {
    "master" = jsonencode({
      username = local.master_username
      password = local.master_password
      jdbcUrl  = local.jdbc_url
    })
    "schema-owner" = jsonencode({
      username = local.schema_owner_username
      password = local.schema_owner_password
      jdbcUrl  = local.jdbc_url
    })
  }

  ssm_endpoint_name = "address-lookup-${var.environment}-ssm"
  ssm_endpoint_url  = "https://${aws_vpc_endpoint.ssm.dns_entry[0].dns_name}"
}
