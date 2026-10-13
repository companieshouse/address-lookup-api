terraform {
  backend "s3" {
  }

  required_version = ">= 1.3, < 2.0"

  required_providers {
    aws = {
      source  = "hashicorp/aws"
      version = ">= 6.0, < 7.0"
    }

    vault = {
      source  = "hashicorp/vault"
      version = ">= 5.0, < 6.0"
    }
  }
}

provider "aws" {
  region = var.aws_region
}

module "lambda" {
  source = "git@github.com:companieshouse/terraform-modules//aws/lambda?ref=1.0.442"

  environment    = var.environment
  function_name  = local.function_name
  lambda_runtime = var.lambda_runtime
  lambda_handler = var.lambda_handler_name

  lambda_code_s3_bucket = var.release_bucket_name
  lambda_code_s3_key    = var.release_artifact_key

  lambda_memory_size         = var.lambda_memory_size
  lambda_timeout_seconds     = var.lambda_timeout_seconds
  lambda_logs_retention_days = var.lambda_logs_retention_days

  lambda_env_vars = {
    SNAPSHOT_BUCKET          = var.snapshot_bucket_name
    DB_CREDENTIALS_PARAMETER = local.credentials_parameter_name
    LOCK_TIMEOUT             = var.lock_timeout
    # Break-glass only: set to true by hand for a repair-<env> run, then back.
    ALLOW_REPAIR = "false"
    # The SSM endpoint (terraform/groups/aurora) has private DNS off, so the
    # AWS SDK is pointed at it explicitly.
    AWS_ENDPOINT_URL_SSM = local.ssm_endpoint_url
  }

  # One migration at a time and never an automatic retry: Concourse invokes the
  # function synchronously and decides what happens next. There are no event
  # rules, schedules or queues.
  lambda_reserved_concurrent_executions = 1
  lambda_maximum_retry_attempts         = 0

  lambda_permissions = [
    {
      statement_id = "AllowConcourseInvoke"
      action       = "lambda:InvokeFunction"
      principal    = data.aws_iam_user.concourse.arn
      source_arn   = null
    }
  ]

  additional_policies = [data.aws_iam_policy_document.db_migrate.json]

  # No CIDR egress at all, so no route to the internet. The only egress rules
  # are added in network.tf: Aurora, the S3 gateway endpoint and the SSM endpoint.
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

resource "aws_cloudwatch_metric_alarm" "errors" {
  alarm_name        = "${module.lambda.lambda_function_name}-errors"
  alarm_description = "A ${module.lambda.lambda_function_name} invocation failed. Check the Concourse update-${var.environment} job and the function's logs."

  namespace   = "AWS/Lambda"
  metric_name = "Errors"
  dimensions = {
    FunctionName = module.lambda.lambda_function_name
  }

  statistic           = "Sum"
  period              = 60
  evaluation_periods  = 1
  comparison_operator = "GreaterThanThreshold"
  threshold           = 0
  treat_missing_data  = "notBreaching"

  alarm_actions = [data.aws_sns_topic.alarms.arn]
  ok_actions    = [data.aws_sns_topic.alarms.arn]

  tags = merge(module.iac_tags.tags, module.owner_tags.tags)
}

module "iac_tags" {
  source = "git@github.com:companieshouse/terraform-modules//aws/tagging/iac?ref=1.0.442"

  group           = "db-migrate"
  source_code_url = "https://github.com/companieshouse/address-lookup-api.git"
}

module "owner_tags" {
  source = "git@github.com:companieshouse/terraform-modules//aws/tagging/owner?ref=1.0.442"

  platform_owner = "development"
}
