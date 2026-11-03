data "aws_caller_identity" "current" {}

data "aws_vpc" "vpc" {
  filter {
    name   = "tag:Name"
    values = [local.vpc_name]
  }
}

data "aws_subnets" "data" {
  filter {
    name   = "tag:Name"
    values = [local.data_subnet_pattern]
  }
}

data "vault_generic_secret" "aurora" {
  path = "applications/${var.aws_profile}/${var.environment}/${local.stack_name}-stack/${local.service_name}"
}

data "vault_generic_secret" "stack_secrets" {
  path = "applications/${var.aws_profile}/${var.environment}/${local.stack_name}-stack"
}

data "aws_kms_key" "parameters" {
  key_id = local.parameters_kms_alias
}

# The bootstrap Lambda reads both credentials parameters; nothing else.
data "aws_iam_policy_document" "db_bootstrap" {
  statement {
    sid       = "ReadDatabaseCredentials"
    effect    = "Allow"
    actions   = ["ssm:GetParameter"]
    resources = [local.master_parameter_arn, local.schema_owner_parameter_arn]
  }

  statement {
    sid       = "DecryptDatabaseCredentials"
    effect    = "Allow"
    actions   = ["kms:Decrypt"]
    resources = [data.aws_kms_key.parameters.arn]
  }
}

# What may pass through the SSM endpoint: the bootstrap Lambda may read both parameters, the
# db-migrate Lambda (terraform/groups/db-migrate) only the schema owner's.
data "aws_iam_policy_document" "ssm_endpoint" {
  statement {
    sid       = "BootstrapReadsDatabaseCredentials"
    effect    = "Allow"
    actions   = ["ssm:GetParameter"]
    resources = [local.master_parameter_arn, local.schema_owner_parameter_arn]

    principals {
      type        = "AWS"
      identifiers = ["*"]
    }

    condition {
      test     = "ArnEquals"
      variable = "aws:PrincipalArn"
      values   = [module.db_bootstrap.lambda_role_arn]
    }
  }

  statement {
    sid       = "DbMigrateReadsSchemaOwnerCredentials"
    effect    = "Allow"
    actions   = ["ssm:GetParameter"]
    resources = [local.schema_owner_parameter_arn]

    principals {
      type        = "AWS"
      identifiers = ["*"]
    }

    condition {
      test     = "ArnEquals"
      variable = "aws:PrincipalArn"
      values   = [local.db_migrate_role_arn]
    }
  }
}
