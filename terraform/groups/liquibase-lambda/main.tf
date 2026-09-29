terraform {
  backend "s3" {
  }

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
}

provider "aws" {
  region = var.aws_region
}

# Vault remains the source of truth. Terraform copies the database credentials
# into Parameter Store, where the function reads them at invocation time.
module "secrets" {
  source = "git@github.com:companieshouse/terraform-modules//aws/parameter-store?ref=1.0.434"

  name_prefix = local.parameter_prefix
  kms_key_id  = data.aws_kms_key.kms_key.id
  secrets     = local.liquibase_secrets
}

module "lambda" {
  source = "git@github.com:companieshouse/terraform-modules.git//aws/lambda?ref=ALS-51/Liquibase-Lamba-Support"

  environment    = var.environment
  function_name  = local.lambda_function_name
  lambda_runtime = var.lambda_runtime
  lambda_handler = var.lambda_handler_name

  lambda_code_s3_bucket = var.release_bucket_name
  lambda_code_s3_key    = var.release_artifact_key

  lambda_memory_size         = var.lambda_memory_size
  lambda_timeout_seconds     = var.lambda_timeout_seconds
  lambda_logs_retention_days = var.lambda_logs_retention_days
  lambda_architectures       = var.lambda_architectures

  lambda_env_vars = {
    CHANGELOG_BUCKET      = var.release_bucket_name
    CHANGELOG_KEY_PREFIX  = local.release_key_prefix
    DB_HOST               = local.database_endpoint
    DB_PORT               = tostring(local.database_port)
    DB_NAME               = local.database_name
    DB_USERNAME_PARAMETER = "/${local.parameter_prefix}/db_username"
    DB_PASSWORD_PARAMETER = "/${local.parameter_prefix}/db_password"
    LIQUIBASE_CONTEXTS    = "aws"
    # Stop UPDATE at least this long before the timeout, so a slow migration
    # returns PARTIAL with the Liquibase lock released instead of being killed.
    MIN_REMAINING_MILLIS     = "120000"
    STATEMENT_TIMEOUT_MILLIS = "780000"
  }

  # One migration at a time, and never an automatic retry: Concourse invokes
  # the function synchronously and decides what happens next. There are no
  # event rules and no resource policy, so nothing else can invoke it.
  lambda_reserved_concurrent_executions = 1
  lambda_maximum_retry_attempts         = 0

  additional_policies       = local.additional_iam_policies_json
  lambda_ssm_parameter_arns = local.parameter_arns

  # HTTPS for S3 and Parameter Store. PostgreSQL is added below, to the Aurora
  # security group only.
  lambda_sg_egress_rule = {
    from_port   = 443
    to_port     = 443
    protocol    = "tcp"
    cidr_blocks = ["0.0.0.0/0"]
  }

  lambda_vpc_access_subnet_ids = local.lambda_vpc_access_subnet_ids
  lambda_vpc_id                = data.aws_vpc.vpc.id

  tags = merge(module.iac_tags.tags, module.owner_tags.tags)
}

resource "aws_vpc_security_group_egress_rule" "lambda_to_aurora" {
  security_group_id = module.lambda.security_group_id
  description       = "PostgreSQL to the address-lookup Aurora cluster"

  referenced_security_group_id = data.aws_security_group.aurora.id
  ip_protocol                  = "tcp"
  from_port                    = local.database_port
  to_port                      = local.database_port
}

resource "aws_vpc_security_group_ingress_rule" "aurora_from_lambda" {
  security_group_id = data.aws_security_group.aurora.id
  description       = "address-lookup-api Liquibase Lambda"

  referenced_security_group_id = module.lambda.security_group_id
  ip_protocol                  = "tcp"
  from_port                    = local.database_port
  to_port                      = local.database_port
}

module "iac_tags" {
  source = "git@github.com:companieshouse/terraform-modules//aws/tagging/iac?ref=1.0.434"

  group           = "liquibase-lambda"
  source_code_url = "https://github.com/companieshouse/address-lookup-api.git"
}

module "owner_tags" {
  source = "git@github.com:companieshouse/terraform-modules//aws/tagging/owner?ref=1.0.434"

  platform_owner = "development"
}
