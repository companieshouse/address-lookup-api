# The SSM interface endpoint both migration Lambdas use for their credentials.
#
# The data subnets route 0.0.0.0/0 to the transit gateway, so the Lambdas' security groups are what
# keep them off the internet: no CIDR egress, only Aurora and this endpoint. Private DNS is off,
# because turning it on would send every SSM call in the shared VPC to an endpoint that only admits
# these two functions; the Lambdas are pointed at it with AWS_ENDPOINT_URL_SSM instead.
# terraform/groups/db-migrate looks the endpoint and its security group up by their Name tags and
# adds its own ingress rule.

resource "aws_security_group" "ssm_endpoint" {
  name        = "${local.ssm_endpoint_name}-endpoint"
  description = "SSM interface endpoint used only by the address-lookup ${var.environment} migration Lambdas"
  vpc_id      = data.aws_vpc.vpc.id

  tags = merge(module.iac_tags.tags, module.owner_tags.tags, {
    Name = "${local.ssm_endpoint_name}-endpoint"
  })
}

resource "aws_vpc_endpoint" "ssm" {
  vpc_id              = data.aws_vpc.vpc.id
  service_name        = "com.amazonaws.${var.aws_region}.ssm"
  vpc_endpoint_type   = "Interface"
  subnet_ids          = data.aws_subnets.data.ids
  security_group_ids  = [aws_security_group.ssm_endpoint.id]
  private_dns_enabled = false
  policy              = data.aws_iam_policy_document.ssm_endpoint.json

  tags = merge(module.iac_tags.tags, module.owner_tags.tags, {
    Name = local.ssm_endpoint_name
  })
}

resource "aws_vpc_security_group_ingress_rule" "ssm_endpoint_from_db_bootstrap" {
  security_group_id = aws_security_group.ssm_endpoint.id
  description       = "HTTPS from ${local.db_bootstrap_lambda_name}"

  referenced_security_group_id = module.db_bootstrap.security_group_id
  ip_protocol                  = "tcp"
  from_port                    = 443
  to_port                      = 443
}

resource "aws_vpc_security_group_egress_rule" "db_bootstrap_to_ssm_endpoint" {
  security_group_id = module.db_bootstrap.security_group_id
  description       = "HTTPS to the SSM interface endpoint (credentials)"

  referenced_security_group_id = aws_security_group.ssm_endpoint.id
  ip_protocol                  = "tcp"
  from_port                    = 443
  to_port                      = 443
}

resource "aws_vpc_security_group_egress_rule" "db_bootstrap_to_aurora" {
  security_group_id = module.db_bootstrap.security_group_id
  description       = "PostgreSQL to the address-lookup Aurora cluster"

  referenced_security_group_id = module.aurora_postgres.security_group_id
  ip_protocol                  = "tcp"
  from_port                    = local.database_port
  to_port                      = local.database_port
}

resource "aws_vpc_security_group_ingress_rule" "aurora_from_db_bootstrap" {
  security_group_id = module.aurora_postgres.security_group_id
  description       = "address-lookup db-bootstrap Lambda"

  referenced_security_group_id = module.db_bootstrap.security_group_id
  ip_protocol                  = "tcp"
  from_port                    = local.database_port
  to_port                      = local.database_port
}
