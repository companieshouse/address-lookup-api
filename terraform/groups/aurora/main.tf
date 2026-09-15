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
    postgresql = {
      source = "cyrilgdn/postgresql"
      version = "~> 1.27"
    }
  }
  backend "s3" {}
}

module "aurora_postgres" {
  source = "git@github.com:companieshouse/terraform-modules//aws/aurora?ref=1.0.397"

  service        = local.service_name
  db_name        = local.database_name
  environment    = var.environment
  engine         = "aurora-postgresql"
  engine_version = "18.3"
  vpc_id         = data.aws_vpc.vpc.id

  master_username = local.master_username
  master_password = local.master_password

  subnet_ids = data.aws_subnets.data.ids
  instances  = var.instances

  maintenance_day        = "sun"
  maintenance_start_hour = 0

  iac_tags   = module.iac_tags.tags
  owner_tags = module.owner_tags.tags
}

provider postgresql {
  host             = module.aurora_postgres.cluster_endpoint
  port             = 5432
  database         = local.database_name
  username         = local.master_username
  password         = local.master_password
  sslmode          = "require"
  connect_timeout  = 30
  expected_version = "18.3"
}

resource "postgresql_extension" "postgis" {
  database = local.database_name
  name     = "postgis"
  version  = "3.6.1"

  depends_on = [module.aurora_postgres]
}

module "iac_tags" {
  source = "git@github.com:companieshouse/terraform-modules//aws/tagging/iac?ref=1.0.397"

  group           = "aurora"
  source_code_url = "https://github.com/companieshouse/address-lookup-api.git"
}

module "owner_tags" {
  source = "git@github.com:companieshouse/terraform-modules//aws/tagging/owner?ref=1.0.397"

  platform_owner = "development"
}
