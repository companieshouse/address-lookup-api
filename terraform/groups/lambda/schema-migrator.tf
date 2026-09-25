# The schema migrator: the only path by which the Aurora schema changes.
#
#   merge --> Concourse --> release bucket (address-lookup-db-schema-<v>.zip)
#                  |
#                  +--invoke--> schema migrator --> Parameter Store (credentials)
#                                     |         --> release bucket (changelog)
#                                     +--Liquibase--> Aurora (DATABASECHANGELOG is the history)
#
# Nobody connects to the database to change it. The function has no event
# sources and no resource policy: it runs only when Concourse invokes it with
# a released version and that release's SHA-256.
#
# CREATE EXTENSION postgis needs rds_superuser, which today only the cluster's
# master user has, so the migrator uses the master credentials from Vault.

locals {
  schema_migrator_name = "${local.name_prefix}-schema-migrator"

  # aws/parameter-store names parameters /<name_prefix>/<key>.
  schema_migrator_parameter_prefix = "${local.schema_migrator_name}-${var.environment}"
  schema_migrator_parameter_arns = [
    for key in ["db_username", "db_password"] :
    "arn:${data.aws_partition.current.partition}:ssm:${var.aws_region}:${data.aws_caller_identity.aws_identity.account_id}:parameter/${local.schema_migrator_parameter_prefix}/${key}"
  ]

  database_secrets_path = "${local.stack_secrets_path}/${local.database_service_name}"
}

# The credentials get their own key, so the acquisition functions could not
# decrypt them even if their policies were widened to these parameters.
module "schema_migrator_kms" {
  source = "git@github.com:companieshouse/terraform-modules//aws/kms?ref=1.0.434"

  description   = "Encrypts the ${var.environment} address-lookup schema migrator's database credentials in Parameter Store"
  kms_key_alias = "${var.environment}/${local.schema_migrator_name}"

  tags = merge(module.iac_tags.tags, module.owner_tags.tags)
}

# Vault remains the source of truth. Terraform copies the credentials into
# Parameter Store, where the function reads them at invocation time.
module "schema_migrator_secrets" {
  source = "git@github.com:companieshouse/terraform-modules//aws/parameter-store?ref=1.0.434"

  name_prefix = local.schema_migrator_parameter_prefix
  kms_key_id  = module.schema_migrator_kms.key_id

  secrets = nonsensitive({
    db_username = data.vault_generic_secret.database_secrets.data["master_username"]
    db_password = data.vault_generic_secret.database_secrets.data["master_password"]
  })
}

# Needs the terraform-modules release cut from ALS-51/Liquibase-Lambda-Impl
# (lambda_architectures, lambda_reserved_concurrent_executions,
# lambda_maximum_retry_attempts, lambda_ssm_parameter_arns). 1.0.434 predates
# it; update this ref to the tag that release is given.
module "schema_migrator" {
  source = "git@github.com:companieshouse/terraform-modules.git//aws/lambda?ref=1.0.435"

  environment    = var.environment
  function_name  = local.schema_migrator_name
  lambda_runtime = var.lambda_runtime
  lambda_handler = "uk.gov.companieshouse.addresslookup.lamda.Handler::handleRequest"

  lambda_code_s3_bucket = var.release_bucket_name
  lambda_code_s3_key    = "${var.lambda_artifact_key_prefix}/${local.name_prefix}-lambda-schema-migrator-${var.lambda_version}.zip"

  lambda_memory_size         = 1024
  lambda_timeout_seconds     = 900
  lambda_logs_retention_days = var.lambda_logs_retention_days
  lambda_architectures       = ["arm64"]

  lambda_env_vars = {
    CHANGELOG_BUCKET      = var.release_bucket_name
    CHANGELOG_KEY_PREFIX  = var.lambda_artifact_key_prefix
    DB_HOST               = local.database_endpoint
    DB_PORT               = tostring(local.database_port)
    DB_NAME               = local.database_name
    DB_USERNAME_PARAMETER = "/${local.schema_migrator_parameter_prefix}/db_username"
    DB_PASSWORD_PARAMETER = "/${local.schema_migrator_parameter_prefix}/db_password"
    LIQUIBASE_CONTEXTS    = "aws"
    # Stop UPDATE at least this long before the timeout, so a slow migration
    # returns PARTIAL with the Liquibase lock released instead of being killed.
    MIN_REMAINING_MILLIS     = "120000"
    STATEMENT_TIMEOUT_MILLIS = "780000"
  }

  # One migration at a time, and never an automatic retry: Concourse invokes
  # synchronously and decides what happens next.
  lambda_reserved_concurrent_executions = 1
  lambda_maximum_retry_attempts         = 0

  additional_policies       = [data.aws_iam_policy_document.schema_migrator.json]
  lambda_ssm_parameter_arns = local.schema_migrator_parameter_arns

  lambda_vpc_id                = data.aws_vpc.vpc.id
  lambda_vpc_access_subnet_ids = local.application_subnet_ids

  # HTTPS for S3 and Parameter Store. Aurora is added below, to its security
  # group only.
  lambda_sg_egress_rule = {
    from_port   = 443
    to_port     = 443
    protocol    = "tcp"
    cidr_blocks = ["0.0.0.0/0"]
  }

  tags = merge(module.iac_tags.tags, module.owner_tags.tags)
}

resource "aws_vpc_security_group_egress_rule" "schema_migrator_to_aurora" {
  security_group_id = module.schema_migrator.security_group_id
  description       = "PostgreSQL to the address-lookup Aurora cluster"

  referenced_security_group_id = data.aws_security_group.aurora.id
  ip_protocol                  = "tcp"
  from_port                    = local.database_port
  to_port                      = local.database_port
}

resource "aws_vpc_security_group_ingress_rule" "schema_migrator_to_aurora" {
  security_group_id = data.aws_security_group.aurora.id
  description       = "address-lookup schema migrator Lambda"

  referenced_security_group_id = module.schema_migrator.security_group_id
  ip_protocol                  = "tcp"
  from_port                    = local.database_port
  to_port                      = local.database_port
}
