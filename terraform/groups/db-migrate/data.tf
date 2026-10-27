data "aws_caller_identity" "current" {}

data "vault_generic_secret" "stack_secrets" {
  path = local.stack_secrets_path
}

data "vault_generic_secret" "database_secrets" {
  path = local.database_secrets_path
}

# Written by aws-shared-services-infrastructure-terraform (groups/infrastructure).
data "vault_generic_secret" "shared_services_kms" {
  path = "aws-accounts/shared-services/kms"
}

data "aws_vpc" "vpc" {
  filter {
    name   = "tag:Name"
    values = [local.vpc_name]
  }
}

# The Aurora cluster's data subnets: private, one per availability zone.
data "aws_subnets" "data" {
  filter {
    name   = "vpc-id"
    values = [data.aws_vpc.vpc.id]
  }

  filter {
    name   = "tag:Name"
    values = [local.data_subnet_pattern]
  }
}

data "aws_rds_cluster" "aurora" {
  cluster_identifier = "${var.environment}-${local.database_service_name}"
}

data "aws_security_group" "aurora" {
  vpc_id = data.aws_vpc.vpc.id

  filter {
    name   = "group-name"
    values = ["${var.environment}-${local.database_service_name}-rds-sg"]
  }
}

# Owned by the VPC, not by this group. Fails the plan if the VPC has none.
data "aws_vpc_endpoint" "s3" {
  vpc_id       = data.aws_vpc.vpc.id
  service_name = "com.amazonaws.${var.aws_region}.s3"
  state        = "available"

  filter {
    name   = "vpc-endpoint-type"
    values = ["Gateway"]
  }
}

data "aws_kms_key" "parameters" {
  key_id = local.parameters_kms_alias
}

# The IAM user whose access keys the environment's Concourse jobs use.
data "aws_iam_user" "concourse" {
  user_name = var.concourse_iam_user_name
}

data "aws_sns_topic" "alarms" {
  name = local.alarm_topic_name
}

data "aws_iam_policy_document" "db_migrate" {
  statement {
    sid       = "ReadSnapshots"
    effect    = "Allow"
    actions   = ["s3:GetObject", "s3:GetObjectVersion"]
    resources = ["arn:aws:s3:::${var.snapshot_bucket_name}/${var.snapshot_prefix}/*"]
  }

  statement {
    sid       = "ReadSchemaOwnerCredentials"
    effect    = "Allow"
    actions   = ["ssm:GetParameter"]
    resources = [local.credentials_parameter_arn]
  }

  statement {
    sid       = "DecryptSchemaOwnerCredentialsAndSnapshots"
    effect    = "Allow"
    actions   = ["kms:Decrypt"]
    resources = local.decrypt_key_arns
  }
}

# Only this function's role, and only its one parameter, through the endpoint.
data "aws_iam_policy_document" "ssm_endpoint" {
  statement {
    sid       = "ReadSchemaOwnerCredentials"
    effect    = "Allow"
    actions   = ["ssm:GetParameter"]
    resources = [local.credentials_parameter_arn]

    principals {
      type        = "AWS"
      identifiers = ["*"]
    }

    condition {
      test     = "ArnEquals"
      variable = "aws:PrincipalArn"
      values   = [module.lambda.lambda_role_arn]
    }
  }
}
