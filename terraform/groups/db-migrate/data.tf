data "aws_vpc" "vpc" {
  filter {
    name   = "tag:Name"
    values = [var.vpc_name]
  }
}

data "aws_subnets" "private" {
  filter {
    name   = "tag:Name"
    values = [var.private_subnet_pattern]
  }
}

data "aws_route_table" "private" {
  for_each  = toset(data.aws_subnets.private.ids)
  subnet_id = each.value
}

data "aws_ssm_parameter" "db_credentials" {
  name            = var.db_credentials_parameter_name
  with_decryption = false
}
