locals {
  stack_name = "common-services" # this must match the stack name the service deploys into

  name_prefix = "address-lookup"

  vpc_name                   = local.stack_secrets["vpc_name"]
  application_subnet_pattern = local.stack_secrets["application_subnet_pattern"]
  application_subnet_ids     = data.aws_subnets.application.ids

  # The Aurora cluster is owned by the aurora group in this repository. Both
  # groups are deployed from here, so the names are known rather than being
  # threaded through remote state.
  database_service_name = "address-rds"
  database_name         = "addressdb"
  database_sg_name      = lower("${var.environment}-${local.database_service_name}-rds-sg")
  database_endpoint     = data.aws_rds_cluster.aurora.endpoint
  database_port         = data.aws_rds_cluster.aurora.port

  # Secrets
  stack_secrets      = data.vault_generic_secret.stack_secrets.data
  stack_secrets_path = "applications/${var.aws_profile}/${var.environment}/${local.stack_name}-stack"
}
