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

  # aws/parameter-store names each parameter /<name_prefix>/<key>, giving
  # /address-lookup/<environment>/db/schema-owner. Only the key is used by its
  # for_each; the value stays sensitive.
  parameter_prefix           = "address-lookup/${var.environment}/db"
  credentials_parameter_name = "/${local.parameter_prefix}/schema-owner"
  credentials_parameter_arn  = "arn:aws:ssm:${var.aws_region}:${data.aws_caller_identity.current.account_id}:parameter${local.credentials_parameter_name}"

  # TLS is always used and enforced by the cluster (rds.force_ssl=1).
  jdbc_url = "jdbc:postgresql://${data.aws_rds_cluster.aurora.endpoint}:${local.database_port}/${local.database_name}?sslmode=require"

  credentials = {
    "schema-owner" = jsonencode({
      username = local.database_secrets["schema_owner_username"]
      password = local.database_secrets["schema_owner_password"]
      jdbcUrl  = local.jdbc_url
    })
  }

  ssm_endpoint_url = "https://${aws_vpc_endpoint.ssm.dns_entry[0].dns_name}"
}
