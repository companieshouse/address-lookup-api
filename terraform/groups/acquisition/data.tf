data "aws_caller_identity" "current" {}

data "vault_generic_secret" "stack_secrets" {
  path = local.stack_secrets_path
}

data "vault_generic_secret" "database_secrets" {
  path = local.database_secrets_path
}

data "vault_generic_secret" "acquisition_secrets" {
  path = local.acquisition_secrets_path
}

# Created by terraform/groups/s3 (ALS-139), which must be applied first: the
# plan fails here until it has been.
data "aws_s3_bucket" "acquisition" {
  bucket = local.bucket_name
}

# The bucket's SSE-KMS key. Parameter Store uses the same key for the
# SecureStrings this group reads.
data "aws_kms_key" "environment" {
  key_id = local.kms_key_id
}

data "aws_sns_topic" "alarms" {
  name = local.alarm_topic_name
}

# ---------------- Upload network ----------------
data "aws_vpc" "vpc" {
  filter {
    name   = "tag:Name"
    values = [local.vpc_name]
  }
}

# The VPC uses its own DNS resolvers rather than AmazonProvidedDNS, so a
# function with no CIDR egress needs explicit DNS egress to them.
data "aws_vpc_dhcp_options" "vpc" {
  dhcp_options_id = data.aws_vpc.vpc.dhcp_options_id
}

# The Aurora cluster's data subnets, as db-migrate uses: they reach Aurora and
# the S3 gateway endpoint.
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

# Owned by the VPC, not by this group.
data "aws_vpc_endpoint" "s3" {
  vpc_id       = data.aws_vpc.vpc.id
  service_name = "com.amazonaws.${var.aws_region}.s3"
  state        = "available"

  filter {
    name   = "vpc-endpoint-type"
    values = ["Gateway"]
  }
}

# Created by terraform/groups/aurora, which must be applied first.
data "aws_vpc_endpoint" "ssm" {
  vpc_id       = data.aws_vpc.vpc.id
  service_name = "com.amazonaws.${var.aws_region}.ssm"
  state        = "available"

  tags = {
    Name = local.ssm_endpoint_name
  }
}

data "aws_security_group" "ssm_endpoint" {
  vpc_id = data.aws_vpc.vpc.id

  tags = {
    Name = "${local.ssm_endpoint_name}-endpoint"
  }
}
