# Network for address-lookup-db-migrate-<env>.
#
# The function runs in the Aurora cluster's data subnets. Those subnets route
# 0.0.0.0/0 to the transit gateway, so the function's security group is what
# keeps it off the internet: it has no CIDR egress, only
#   * 5432 to the Aurora security group,
#   * 443 to the S3 gateway endpoint's prefix list (snapshots), and
#   * 443 to the SSM interface endpoint (credentials).
#
# The S3 gateway endpoint belongs to the VPC and is looked up, not created.
# The SSM endpoint and its security group belong to terraform/groups/aurora,
# which also writes the credentials parameter; they are looked up here and this
# group adds only its own ingress rule. Private DNS is off on that endpoint:
# turning it on would send every SSM call in the shared VPC to an endpoint that
# only admits the migration Lambdas. KMS and CloudWatch Logs endpoints are not
# needed: Parameter Store decrypts the SecureString on the function's behalf,
# and Lambda ships the function's logs itself, outside the VPC.

resource "aws_vpc_security_group_egress_rule" "lambda_to_aurora" {
  security_group_id = module.lambda.security_group_id
  description       = "PostgreSQL to the address-lookup Aurora cluster"

  referenced_security_group_id = data.aws_security_group.aurora.id
  ip_protocol                  = "tcp"
  from_port                    = local.database_port
  to_port                      = local.database_port
}

resource "aws_vpc_security_group_ingress_rule" "aurora_from_lambda" {
  security_group_id = data.aws_security_group.aurora.id
  description       = "address-lookup db-migrate Lambda"

  referenced_security_group_id = module.lambda.security_group_id
  ip_protocol                  = "tcp"
  from_port                    = local.database_port
  to_port                      = local.database_port
}

resource "aws_vpc_security_group_egress_rule" "lambda_to_s3" {
  security_group_id = module.lambda.security_group_id
  description       = "HTTPS to S3 through the VPC gateway endpoint (snapshots)"

  prefix_list_id = data.aws_vpc_endpoint.s3.prefix_list_id
  ip_protocol    = "tcp"
  from_port      = 443
  to_port        = 443
}

resource "aws_vpc_security_group_egress_rule" "lambda_to_ssm_endpoint" {
  security_group_id = module.lambda.security_group_id
  description       = "HTTPS to the SSM interface endpoint (credentials)"

  referenced_security_group_id = data.aws_security_group.ssm_endpoint.id
  ip_protocol                  = "tcp"
  from_port                    = 443
  to_port                      = 443
}


resource "aws_vpc_security_group_ingress_rule" "ssm_endpoint_from_lambda" {
  security_group_id = data.aws_security_group.ssm_endpoint.id
  description       = "HTTPS from ${local.lambda_name}"

  referenced_security_group_id = module.lambda.security_group_id
  ip_protocol                  = "tcp"
  from_port                    = 443
  to_port                      = 443
}
