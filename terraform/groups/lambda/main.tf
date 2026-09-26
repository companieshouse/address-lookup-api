provider "aws" {
  region = var.aws_region
}

terraform {
  required_version = ">= 1.3, < 2.0"
  required_providers {
    aws = {
      source  = "hashicorp/aws"
      version = ">= 6.0, < 7.0"
    }
    vault = {
      source  = "hashicorp/vault"
      version = ">= 5.0, < 6.0"
    }
  }
  backend "s3" {}
}


module "liquibase_secrets" {
  source = "git@github.com:companieshouse/terraform-modules//aws/parameter-store?ref=1.0.434"

  name_prefix = local.liquibase_parameter_prefix
  kms_key_id  = data.aws_kms_key.kms_key.id

  secrets = sensitive({
    db_username = data.vault_generic_secret.database_secrets.data["master_username"]
    db_password = data.vault_generic_secret.database_secrets.data["master_password"]
  })
}


module "liquibase_lambda" {
  source = "git@github.com:companieshouse/terraform-modules.git//aws/lambda?ref=ALS-51/Liquibase-Lambda-Impl"

  environment    = var.environment
  function_name  = local.lambda_function_name
  lambda_runtime = var.lambda_runtime
  lambda_handler = "uk.gov.companieshouse.addresslookup.lambda.Handler::handleRequest"

  lambda_code_s3_bucket = var.release_bucket_name
  lambda_code_s3_key    = "${var.lambda_artifact_key_prefix}/${local.lambda_function_name}-lambda-${var.lambda_version}.zip"

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
    DB_USERNAME_PARAMETER = "/${local.liquibase_parameter_prefix}/db_username"
    DB_PASSWORD_PARAMETER = "/${local.liquibase_parameter_prefix}/db_password"
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

  additional_policies       = [data.aws_iam_policy_document.liquibase.json]
  lambda_ssm_parameter_arns = local.liquibase_parameter_arns

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

resource "aws_vpc_security_group_egress_rule" "liquibase_to_aurora" {
  security_group_id = module.liquibase_lambda.security_group_id
  description       = "PostgreSQL to the address-lookup Aurora cluster"

  referenced_security_group_id = data.aws_security_group.aurora.id
  ip_protocol                  = "tcp"
  from_port                    = local.database_port
  to_port                      = local.database_port
}

resource "aws_vpc_security_group_ingress_rule" "liquibase_to_aurora" {
  security_group_id = data.aws_security_group.aurora.id
  description       = "address-lookup-api liquibase Lambda"

  referenced_security_group_id = module.liquibase_lambda.security_group_id
  ip_protocol                  = "tcp"
  from_port                    = local.database_port
  to_port                      = local.database_port
}


module "iac_tags" {
  source = "git@github.com:companieshouse/terraform-modules//aws/tagging/iac?ref=1.0.434"

  group           = "lambda"
  source_code_url = "https://github.com/companieshouse/address-lookup-api.git"
}

module "owner_tags" {
  source = "git@github.com:companieshouse/terraform-modules//aws/tagging/owner?ref=1.0.434"

  platform_owner = "development"
}
