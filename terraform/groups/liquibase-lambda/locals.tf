locals {

  service_name         = "address-lookup-api"
  stack_name           = "common-services-stack" # the stack the service and its Aurora cluster deploy into
  lambda_function_name = "address-lookup-liquibase-lambda"
  kms_alias            = "alias/aws/ssm"

  # Release artefacts, and the changelogs the function applies, are published
  # under this prefix in the release bucket.
  release_key_prefix = local.service_name

  vpc_name                     = local.stack_secrets["vpc_name"]
  lambda_vpc_access_subnet_ids = data.aws_subnets.application.ids
  application_subnet_pattern   = local.stack_secrets["application_subnet_pattern"]

  additional_iam_policies_json = [data.aws_iam_policy_document.read_changelogs.json]

  database_service_name = "address-rds"
  database_name         = "addressdb"
  database_sg_name      = "${var.environment}-${local.database_service_name}-rds-sg"
  database_endpoint     = data.aws_rds_cluster.aurora.endpoint
  database_port         = data.aws_rds_cluster.aurora.port

  # Secrets
  stack_secrets         = data.vault_generic_secret.stack_secrets.data
  stack_secrets_path    = "applications/${var.aws_profile}/${var.environment}/${local.stack_name}"
  database_secrets      = data.vault_generic_secret.database_secrets.data
  database_secrets_path = "${local.stack_secrets_path}/${local.database_service_name}"

  # CREATE EXTENSION postgis needs rds_superuser, which today only the
  # cluster's master user has. The values stay sensitive; only the keys are
  # used by the parameter-store module's for_each.
  liquibase_secrets = {
    db_username = local.database_secrets["master_username"]
    db_password = local.database_secrets["master_password"]
  }

  # aws/parameter-store names parameters /<name_prefix>/<key>.
  parameter_prefix = "${local.lambda_function_name}-${var.environment}"
  parameter_arns = [
    for key in keys(local.liquibase_secrets) :
    "arn:aws:ssm:${var.aws_region}:${data.aws_caller_identity.aws_identity.account_id}:parameter/${local.parameter_prefix}/${key}"
  ]
}
