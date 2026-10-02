data "vault_generic_secret" "stack_secrets" {
  path = local.stack_secrets_path
}

data "vault_generic_secret" "database_secrets" {
  path = local.database_secrets_path
}

data "aws_kms_key" "kms_key" {
  key_id = local.kms_alias
}

data "aws_caller_identity" "aws_identity" {}

# Released changelog archives only; not the service or Lambda artefacts
# alongside them in the release bucket.
data "aws_iam_policy_document" "read_changelogs" {
  statement {
    sid       = "ReadReleasedChangelogs"
    effect    = "Allow"
    actions   = ["s3:GetObject"]
    resources = ["arn:aws:s3:::${var.release_bucket_name}/${local.release_key_prefix}/address-lookup-db-schema-*.zip"]
  }
}

data "aws_vpc" "vpc" {
  filter {
    name   = "tag:Name"
    values = [local.vpc_name]
  }
}

#Get application subnet IDs
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

data "aws_rds_cluster" "aurora" {
  cluster_identifier = "${var.environment}-${local.database_service_name}"
}

data "aws_security_group" "aurora" {
  vpc_id = data.aws_vpc.vpc.id

  filter {
    name   = "group-name"
    values = [local.database_sg_name]
  }
}
