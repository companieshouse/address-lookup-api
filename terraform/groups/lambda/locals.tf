locals {
  stack_name                 = "common-services" # this must match the stack name the service deploys into
  kms_alias                  = "alias/aws/ssm"
  service_name               = "address-lookup-api"
  lambda_function_name       = lower("${local.service_name}-liquibase")
  liquibase_parameter_prefix = "${local.lambda_function_name}-${var.environment}"

  vpc_name                   = local.stack_secrets["vpc_name"]
  application_subnet_pattern = local.stack_secrets["application_subnet_pattern"]
  application_subnet_ids     = data.aws_subnets.application.ids

  database_service_name = "address-rds"
  database_name         = "addressdb"
  database_sg_name      = lower("${var.environment}-${local.database_service_name}-rds-sg")
  database_endpoint     = data.aws_rds_cluster.aurora.endpoint
  database_port         = data.aws_rds_cluster.aurora.port

  # Secrets
  stack_secrets      = data.vault_generic_secret.stack_secrets.data
  stack_secrets_path = "applications/${var.aws_profile}/${var.environment}/${local.stack_name}-stack"

  # aws/parameter-store names parameters /<name_prefix>/<key>.
  liquibase_parameter_arns = [
    for key in ["db_username", "db_password"] :
    "arn:${data.aws_partition.current.partition}:ssm:${var.aws_region}:${data.aws_caller_identity.aws_identity.account_id}:parameter/${local.liquibase_parameter_prefix}/${key}"
  ]

  database_secrets_path = "${local.stack_secrets_path}/${local.database_service_name}"

}
