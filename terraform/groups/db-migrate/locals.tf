locals {
  function_name = "address-lookup-${var.environment}-db-migrate"

  private_subnet_ids = data.aws_subnets.private.ids
  route_table_ids    = distinct([for rt in data.aws_route_table.private : rt.id])

  interface_endpoints = toset(["ssm", "kms", "logs"])
}
