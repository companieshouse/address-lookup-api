# Network for address-lookup-ngd-upload-<env>.
#
# The function runs in the Aurora cluster's data subnets, as db-migrate does.
# Its security group has no CIDR egress, only
#   * the database port to the Aurora security group,
#   * 443 to the S3 gateway endpoint's prefix list (acquisition bucket),
#   * 443 to the SSM interface endpoint (credentials), and
#   * 53 (UDP and TCP) to the VPC's DHCP DNS resolvers.
#
# The S3 gateway endpoint belongs to the VPC. The SSM endpoint and its security
# group belong to terraform/groups/aurora; this group adds only its own ingress
# rules on them and on the Aurora security group.

resource "aws_vpc_security_group_egress_rule" "lambda_to_aurora" {
  security_group_id = module.upload.security_group_id
  description       = "PostgreSQL to the address-lookup Aurora cluster"

  referenced_security_group_id = data.aws_security_group.aurora.id
  ip_protocol                  = "tcp"
  from_port                    = local.database_port
  to_port                      = local.database_port
}

resource "aws_vpc_security_group_ingress_rule" "aurora_from_lambda" {
  security_group_id = data.aws_security_group.aurora.id
  description       = "address-lookup acquisition Upload Lambda"

  referenced_security_group_id = module.upload.security_group_id
  ip_protocol                  = "tcp"
  from_port                    = local.database_port
  to_port                      = local.database_port
}

resource "aws_vpc_security_group_egress_rule" "lambda_to_s3" {
  security_group_id = module.upload.security_group_id
  description       = "HTTPS to S3 through the VPC gateway endpoint (acquisition bucket)"

  prefix_list_id = data.aws_vpc_endpoint.s3.prefix_list_id
  ip_protocol    = "tcp"
  from_port      = 443
  to_port        = 443
}

resource "aws_vpc_security_group_egress_rule" "lambda_to_ssm_endpoint" {
  security_group_id = module.upload.security_group_id
  description       = "HTTPS to the SSM interface endpoint (credentials)"

  referenced_security_group_id = data.aws_security_group.ssm_endpoint.id
  ip_protocol                  = "tcp"
  from_port                    = 443
  to_port                      = 443
}


resource "aws_vpc_security_group_ingress_rule" "ssm_endpoint_from_lambda" {
  security_group_id = data.aws_security_group.ssm_endpoint.id
  description       = "HTTPS from ${local.lambda_names.upload}"

  referenced_security_group_id = module.upload.security_group_id
  ip_protocol                  = "tcp"
  from_port                    = 443
  to_port                      = 443
}

resource "aws_vpc_security_group_egress_rule" "lambda_dns" {
  for_each = local.dns_egress

  security_group_id = module.upload.security_group_id
  description       = "DNS (${upper(each.value.ip_protocol)}) to VPC resolver ${each.value.cidr_ipv4}"

  cidr_ipv4   = each.value.cidr_ipv4
  ip_protocol = each.value.ip_protocol
  from_port   = 53
  to_port     = 53
}
