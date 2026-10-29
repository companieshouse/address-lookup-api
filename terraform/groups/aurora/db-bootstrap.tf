# Prepares the database before the first Flyway run (ALS-90), as the master user:
#   * CREATE EXTENSION IF NOT EXISTS postgis, so the V2026.001 baseline's own CREATE EXTENSION is a no-op
#   * creates (or updates) the schema-owner role: LOGIN, password from Vault, CONNECT and CREATE on the
#     database and CREATE on schema public (flyway_schema_history). Not a superuser, not rds_superuser.
#   * verifies: postgis installed, owner is not the master, the database has no application objects
#     unless flyway_schema_history exists, rds.force_ssl=1, ssl_min_protocol_version=TLSv1.3, and a
#     TLS 1.2 handshake is refused while TLS 1.3 succeeds.
# Any failure fails the invocation and so this apply. Only Terraform invokes it: there are no event
# sources and no invoke permissions. Code: lambdas/db-bootstrap in this repository.
module "db_bootstrap" {
  source = "git@github.com:companieshouse/terraform-modules//aws/lambda?ref=1.0.442"

  environment    = var.environment
  function_name  = local.db_bootstrap_function_name
  lambda_runtime = "java21"
  lambda_handler = "uk.gov.companieshouse.addresslookup.dbbootstrap.Handler::handleRequest"

  lambda_code_s3_bucket = var.release_bucket_name
  lambda_code_s3_key    = var.db_bootstrap_release_artifact_key

  lambda_memory_size         = var.db_bootstrap_memory_size
  lambda_timeout_seconds     = var.db_bootstrap_timeout_seconds
  lambda_logs_retention_days = 90

  lambda_env_vars = {
    MASTER_CREDENTIALS_PARAMETER       = local.master_parameter_name
    SCHEMA_OWNER_CREDENTIALS_PARAMETER = local.schema_owner_parameter_name
    AWS_ENDPOINT_URL_SSM               = local.ssm_endpoint_url
  }

  lambda_reserved_concurrent_executions = 1
  lambda_maximum_retry_attempts         = 0

  additional_policies = [data.aws_iam_policy_document.db_bootstrap.json]

  # No CIDR egress, so no route to the internet; the egress rules are in network.tf.
  lambda_sg_egress_rule = {
    from_port   = 443
    to_port     = 443
    protocol    = "tcp"
    cidr_blocks = []
  }

  lambda_vpc_access_subnet_ids = data.aws_subnets.data.ids
  lambda_vpc_id                = data.aws_vpc.vpc.id

  tags = merge(module.iac_tags.tags, module.owner_tags.tags)
}

# Runs the bootstrap on the first apply and again whenever a trigger changes: a new Lambda release,
# a new cluster, a schema-owner rotation in Vault or a change to the TLS settings. The result (no
# credentials) is kept in state and in the db_bootstrap_result output.
resource "aws_lambda_invocation" "db_bootstrap" {
  function_name = module.db_bootstrap.lambda_function_name
  input         = jsonencode({ command = "bootstrap" })

  triggers = {
    release_artifact      = var.db_bootstrap_release_artifact_key
    cluster_id            = module.aurora_postgres.cluster_id
    schema_owner_username = local.schema_owner_username
    schema_owner_password = sha256(local.schema_owner_password)
    tls_settings          = jsonencode(local.cluster_parameter_group_settings)
  }

  depends_on = [
    module.aurora_postgres,
    module.credentials,
    aws_vpc_endpoint.ssm,
    aws_vpc_security_group_ingress_rule.ssm_endpoint_from_db_bootstrap,
    aws_vpc_security_group_egress_rule.db_bootstrap_to_ssm_endpoint,
    aws_vpc_security_group_egress_rule.db_bootstrap_to_aurora,
    aws_vpc_security_group_ingress_rule.aurora_from_db_bootstrap,
  ]
}
