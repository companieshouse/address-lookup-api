provider "aws" {
  region = var.aws_region
}

terraform {
  required_version = ">= 1.3, < 2.0"
  required_providers {
    aws = {
      source  = "hashicorp/aws"
      version = ">= 5.0, < 6.0"
    }
    vault = {
      source  = "hashicorp/vault"
      version = ">= 4.0, < 5.0"
    }
  }
  backend "s3" {}
}

module "aurora_postgres" {
  source = "git@github.com:companieshouse/terraform-modules//aws/aurora?ref=1.0.386"

  service        = local.service_name
  environment    = var.environment
  engine         = "aurora-postgresql"
  engine_version = "15.3"
  vpc_id         = data.aws_vpc.vpc.id

  master_username = local.master_username
  master_password = local.master_password


  source_code_url = "https://github.com/companieshouse/address-lookup-api.git"
  platform_owner  = "development"
  group           = "aurora"

  subnet_ids = data.aws_subnets.data.ids
  instances  = var.instances
}
