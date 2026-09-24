provider "aws" {
  region = var.aws_region
}

terraform {
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
  backend "s3" {}
}

# The Ordnance Survey acquisition pipeline.
#
#   discovery --DOWNLOAD--> download --> (GuardDuty) --> scan --UNZIP--> unzip
#                                                          |
#                                                          +--> ready marker
#                                                                  |
#                                                                  v
#                                                                import --> Aurora
#
# Every function is a wake-up handler over durable state held in S3, so each
# one is safely retryable and the scheduled reconciliation sweeps recover from
# any event that is lost, duplicated or delivered out of order.
module "lambda" {
  source = "git@github.com:companieshouse/terraform-modules.git//aws/lambda?ref=1.0.434"

  for_each = local.lambda_functions

  environment    = var.environment
  function_name  = "${local.name_prefix}-${each.key}"
  lambda_runtime = var.lambda_runtime
  lambda_handler = local.handler[each.key]

  lambda_code_s3_bucket = var.release_bucket_name
  lambda_code_s3_key    = local.artifact_key[each.key]

  lambda_memory_size         = each.value.memory_size
  lambda_timeout_seconds     = each.value.timeout
  lambda_logs_retention_days = var.lambda_logs_retention_days
  lambda_architectures       = ["arm64"]

  lambda_env_vars = each.value.env

  # The importer is the single writer against Aurora. Anything above one
  # concurrent execution risks two invocations COPYing the same release.
  lambda_reserved_concurrent_executions = each.key == "import" ? 1 : null

  # The handlers are idempotent but not cheap. One retry is enough; after that
  # the invocation is parked on the dead letter queue and alarmed, because a
  # second failure means something needs a human rather than another attempt.
  lambda_maximum_retry_attempts     = 1
  lambda_on_failure_destination_arn = aws_sqs_queue.dead_letter.arn

  # Content is streamed through memory rather than staged on disk, so the
  # default /tmp is sufficient.

  additional_policies           = each.value.policies
  lambda_cloudwatch_event_rules = each.value.event_rules

  lambda_ssm_parameter_arns = [
    "arn:${data.aws_partition.current.partition}:ssm:${var.aws_region}:${data.aws_caller_identity.aws_identity.account_id}:parameter/lambda-global-${var.environment}/*"
  ]

  lambda_vpc_id                = data.aws_vpc.vpc.id
  lambda_vpc_access_subnet_ids = local.application_subnet_ids

  # Outbound only. Discovery and download need to reach api.os.uk through the
  # NAT gateway; the rest reach S3, EventBridge and Aurora.
  lambda_sg_egress_rule = {
    from_port   = -1
    to_port     = -1
    protocol    = "-1"
    cidr_blocks = ["0.0.0.0/0"]
  }

  tags = merge(module.iac_tags.tags, module.owner_tags.tags)
}

# Only the importer is allowed near the database. The four red zone functions
# have neither credentials nor a path through the Aurora security group.
resource "aws_vpc_security_group_ingress_rule" "importer_to_aurora" {
  security_group_id = data.aws_security_group.aurora.id
  description       = "address-lookup import Lambda"

  referenced_security_group_id = module.lambda["import"].security_group_id
  ip_protocol                  = "tcp"
  from_port                    = local.database_port
  to_port                      = local.database_port
}

module "iac_tags" {
  source = "git@github.com:companieshouse/terraform-modules//aws/tagging/iac?ref=1.0.434"

  group           = "lambda"
  source_code_url = "https://github.com/companieshouse/address-lookup-api.git"
}

module "owner_tags" {
  source = "git@github.com:companieshouse/terraform-modules//aws/tagging/owner?ref=1.0.434"

  platform_owner = "development"
}
