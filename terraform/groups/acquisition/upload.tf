# Upload runs in the VPC and connects straight to Aurora PostgreSQL (no RDS
# Proxy). It uses terraform-modules aws/lambda, as terraform/groups/db-migrate does.
module "upload" {
  source = "git@github.com:companieshouse/terraform-modules//aws/lambda?ref=1.0.442"

  environment    = var.environment
  function_name  = local.function_names.upload
  lambda_runtime = var.lambda_runtime
  lambda_handler = var.upload_handler

  lambda_code_s3_bucket = var.release_bucket_name
  lambda_code_s3_key    = var.upload_artifact_key

  lambda_memory_size         = var.upload_memory_size
  lambda_timeout_seconds     = var.upload_timeout_seconds
  lambda_logs_retention_days = var.lambda_logs_retention_days

  lambda_env_vars = merge(local.common_env_vars.upload, {
    DB_CREDENTIALS_PARAMETER = local.upload_credentials_parameter_name
    # The SSM endpoint (terraform/groups/aurora) has private DNS off, so the
    # AWS SDK is pointed at it explicitly.
    AWS_ENDPOINT_URL_SSM = local.ssm_endpoint_url
  })

  # Limited: each run holds one database connection.
  lambda_reserved_concurrent_executions = var.upload_reserved_concurrency
  lambda_maximum_retry_attempts         = 0

  additional_policies = [data.aws_iam_policy_document.acquisition["upload"].json]

  # No CIDR egress, so no route to the internet. network.tf adds Aurora, the S3
  # gateway endpoint, the SSM endpoint and DNS.
  lambda_sg_egress_rule = {
    from_port   = 443
    to_port     = 443
    protocol    = "tcp"
    cidr_blocks = []
  }

  lambda_vpc_access_subnet_ids = data.aws_subnets.data.ids
  lambda_vpc_id                = data.aws_vpc.vpc.id

  tags = local.tags
}
