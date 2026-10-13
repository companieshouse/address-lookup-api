locals {
  stack_name            = "common-services"
  database_service_name = "address-rds"
  database_name         = "addressdb"
  database_port         = data.aws_rds_cluster.aurora.port

  # terraform-modules aws/lambda names the function <function_name>-<environment>.
  function_name = "address-lookup-db-migrate"
  lambda_name   = "${local.function_name}-${var.environment}"

  stack_secrets_path    = "applications/${var.aws_profile}/${var.environment}/${local.stack_name}-stack"
  database_secrets_path = "${local.stack_secrets_path}/${local.database_service_name}"
  stack_secrets         = data.vault_generic_secret.stack_secrets.data
  database_secrets      = data.vault_generic_secret.database_secrets.data

  vpc_name            = local.stack_secrets["vpc_name"]
  data_subnet_pattern = local.database_secrets["data_subnet_pattern"]

  parameters_kms_alias = "alias/${var.aws_profile}/environment-services-kms"
  decrypt_key_arns = compact([
    data.aws_kms_key.parameters.arn,
    var.snapshot_kms_key_arn,
  ])

  alarm_topic_name = coalesce(var.alarm_topic_name, "${local.stack_name}-${var.environment}-cloudwatch-alarms-notify-topic")

  # Written by terraform/groups/aurora from Vault (aws/parameter-store names it
  # /<name_prefix>/<key>), as {"username","password","jdbcUrl"} with a
  # verify-full jdbcUrl on the writer endpoint.
  credentials_parameter_name = "/address-lookup/${var.environment}/db/schema-owner"
  credentials_parameter_arn  = "arn:aws:ssm:${var.aws_region}:${data.aws_caller_identity.current.account_id}:parameter${local.credentials_parameter_name}"

  # terraform/groups/aurora names the endpoint address-lookup-<env>-ssm and its
  # security group address-lookup-<env>-ssm-endpoint.
  ssm_endpoint_name = "address-lookup-${var.environment}-ssm"
  ssm_endpoint_url  = "https://${data.aws_vpc_endpoint.ssm.dns_entry[0].dns_name}"
}
