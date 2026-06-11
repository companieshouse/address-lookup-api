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
  path  = "applications/${var.aws_profile}/${var.environment}/${local.stack_name}-stack/${local.service_name}"
}

data "vault_generic_secret" "stack_secrets" {
  path  = "applications/${var.aws_profile}/${var.environment}/${local.stack_name}-stack"
}
