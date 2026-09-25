data "vault_generic_secret" "stack_secrets" {
  path = local.stack_secrets_path
}

# The Aurora master credentials, as used by the aurora group. Only the schema
# migrator receives them, via Parameter Store.
data "vault_generic_secret" "database_secrets" {
  path = local.database_secrets_path
}

data "aws_caller_identity" "aws_identity" {}

data "aws_partition" "current" {}

data "aws_vpc" "vpc" {
  filter {
    name   = "tag:Name"
    values = [local.vpc_name]
  }
}

data "aws_subnets" "application" {
  filter {
    name   = "vpc-id"
    values = [data.aws_vpc.vpc.id]
  }

  filter {
    name   = "tag:Name"
    values = [local.application_subnet_pattern]
  }
}

# The aurora group in this repository owns the cluster. Looking it up by its
# well-known identifier keeps the two groups independently applyable without
# introducing a remote state dependency.
data "aws_rds_cluster" "aurora" {
  cluster_identifier = lower("${var.environment}-${local.database_service_name}")
}

data "aws_security_group" "aurora" {
  vpc_id = data.aws_vpc.vpc.id

  filter {
    name   = "group-name"
    values = [local.database_sg_name]
  }
}

data "aws_iam_policy_document" "schema_migrator" {
  # Released changelog archives only; not the service or Lambda artefacts
  # alongside them in the release bucket.
  statement {
    sid     = "ReadReleasedChangelogs"
    effect  = "Allow"
    actions = ["s3:GetObject"]
    resources = [
      "arn:${data.aws_partition.current.partition}:s3:::${var.release_bucket_name}/${var.lambda_artifact_key_prefix}/address-lookup-db-schema-*.zip"
    ]
  }

  # Decrypt the two SecureString parameters, and only through Parameter Store.
  statement {
    sid       = "DecryptDatabaseCredentials"
    effect    = "Allow"
    actions   = ["kms:Decrypt"]
    resources = [module.schema_migrator_kms.key_arn]

    condition {
      test     = "StringEquals"
      variable = "kms:ViaService"
      values   = ["ssm.${var.aws_region}.amazonaws.com"]
    }

    condition {
      test     = "StringEquals"
      variable = "kms:EncryptionContext:PARAMETER_ARN"
      values   = local.schema_migrator_parameter_arns
    }
  }
}
