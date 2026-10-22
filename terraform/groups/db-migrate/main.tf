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
  }
  backend "s3" {}
}

# ---------------------------------------------------------------------------
# Network
# ---------------------------------------------------------------------------

resource "aws_security_group" "db_migrate" {
  name        = "db-migrate"
  description = "db-migrate Lambda"
  vpc_id      = data.aws_vpc.vpc.id

  tags = merge(module.iac_tags.tags, module.owner_tags.tags, { Name = "db-migrate" })
}

resource "aws_security_group" "db_migrate_endpoints" {
  name        = "db-migrate-endpoints"
  description = "Interface VPC endpoints used by db-migrate"
  vpc_id      = data.aws_vpc.vpc.id

  tags = merge(module.iac_tags.tags, module.owner_tags.tags, { Name = "db-migrate-endpoints" })
}

resource "aws_vpc_security_group_egress_rule" "db_migrate_to_aurora" {
  security_group_id            = aws_security_group.db_migrate.id
  referenced_security_group_id = var.aurora_security_group_id
  ip_protocol                  = "tcp"
  from_port                    = 5432
  to_port                      = 5432
  description                  = "Aurora"
}

resource "aws_vpc_security_group_egress_rule" "db_migrate_to_endpoints" {
  security_group_id            = aws_security_group.db_migrate.id
  referenced_security_group_id = aws_security_group.db_migrate_endpoints.id
  ip_protocol                  = "tcp"
  from_port                    = 443
  to_port                      = 443
  description                  = "Interface VPC endpoints"
}

resource "aws_vpc_security_group_egress_rule" "db_migrate_to_s3" {
  security_group_id = aws_security_group.db_migrate.id
  prefix_list_id    = aws_vpc_endpoint.s3.prefix_list_id
  ip_protocol       = "tcp"
  from_port         = 443
  to_port           = 443
  description       = "S3 gateway endpoint"
}

resource "aws_vpc_security_group_ingress_rule" "endpoints_from_db_migrate" {
  security_group_id            = aws_security_group.db_migrate_endpoints.id
  referenced_security_group_id = aws_security_group.db_migrate.id
  ip_protocol                  = "tcp"
  from_port                    = 443
  to_port                      = 443
  description                  = "db-migrate"
}

resource "aws_vpc_security_group_ingress_rule" "aurora_from_db_migrate" {
  security_group_id            = var.aurora_security_group_id
  referenced_security_group_id = aws_security_group.db_migrate.id
  ip_protocol                  = "tcp"
  from_port                    = 5432
  to_port                      = 5432
  description                  = "db-migrate Lambda"
}

# ---------------------------------------------------------------------------
# VPC endpoints
# ---------------------------------------------------------------------------

resource "aws_vpc_endpoint" "s3" {
  vpc_id            = data.aws_vpc.vpc.id
  service_name      = "com.amazonaws.${var.aws_region}.s3"
  vpc_endpoint_type = "Gateway"
  route_table_ids   = local.route_table_ids

  policy = jsonencode({
    Version = "2012-10-17"
    Statement = [{
      Effect    = "Allow"
      Principal = "*"
      Action    = ["s3:GetObject", "s3:GetObjectVersion"]
      Resource  = "arn:aws:s3:::${var.snapshot_bucket_name}/snapshots/*"
    }]
  })

  tags = merge(module.iac_tags.tags, module.owner_tags.tags, { Name = "db-migrate-s3" })
}

resource "aws_vpc_endpoint" "interface" {
  for_each = local.interface_endpoints

  vpc_id              = data.aws_vpc.vpc.id
  service_name        = "com.amazonaws.${var.aws_region}.${each.value}"
  vpc_endpoint_type   = "Interface"
  subnet_ids          = local.private_subnet_ids
  security_group_ids  = [aws_security_group.db_migrate_endpoints.id]
  private_dns_enabled = true

  tags = merge(module.iac_tags.tags, module.owner_tags.tags, { Name = "db-migrate-${each.value}" })
}

# ---------------------------------------------------------------------------
# IAM
# ---------------------------------------------------------------------------

data "aws_iam_policy_document" "assume_role" {
  statement {
    actions = ["sts:AssumeRole"]

    principals {
      type        = "Service"
      identifiers = ["lambda.amazonaws.com"]
    }
  }
}

resource "aws_iam_role" "db_migrate" {
  name               = "${local.function_name}-role"
  assume_role_policy = data.aws_iam_policy_document.assume_role.json

  tags = merge(module.iac_tags.tags, module.owner_tags.tags)
}

data "aws_iam_policy_document" "db_migrate" {
  statement {
    sid       = "ReadSnapshots"
    actions   = ["s3:GetObject", "s3:GetObjectVersion"]
    resources = ["arn:aws:s3:::${var.snapshot_bucket_name}/snapshots/*"]
  }

  statement {
    sid       = "ReadCredentialsParameter"
    actions   = ["ssm:GetParameter"]
    resources = [data.aws_ssm_parameter.db_credentials.arn]
  }

  statement {
    sid       = "Decrypt"
    actions   = ["kms:Decrypt"]
    resources = [var.snapshot_kms_key_arn, var.db_credentials_kms_key_arn]
  }

  statement {
    sid       = "WriteLogs"
    actions   = ["logs:CreateLogStream", "logs:PutLogEvents"]
    resources = ["${aws_cloudwatch_log_group.db_migrate.arn}:*"]
  }
}

resource "aws_iam_role_policy" "db_migrate" {
  name   = local.function_name
  role   = aws_iam_role.db_migrate.id
  policy = data.aws_iam_policy_document.db_migrate.json
}

resource "aws_iam_role_policy_attachment" "vpc_access" {
  role       = aws_iam_role.db_migrate.name
  policy_arn = "arn:aws:iam::aws:policy/service-role/AWSLambdaVPCAccessExecutionRole"
}

# ---------------------------------------------------------------------------
# Logs
# ---------------------------------------------------------------------------

resource "aws_cloudwatch_log_group" "db_migrate" {
  name              = "/aws/lambda/${local.function_name}"
  retention_in_days = var.log_retention_in_days

  tags = merge(module.iac_tags.tags, module.owner_tags.tags)
}

# ---------------------------------------------------------------------------
# Function
# ---------------------------------------------------------------------------

resource "aws_lambda_function" "db_migrate" {
  function_name = local.function_name
  role          = aws_iam_role.db_migrate.arn
  runtime       = var.lambda_runtime
  handler       = "uk.gov.companieshouse.addresslookup.dbmigrate.Handler::handleRequest"
  memory_size   = 1024
  timeout       = 900

  reserved_concurrent_executions = 1

  s3_bucket = var.lambda_artefact_bucket
  s3_key    = var.lambda_artefact_key

  environment {
    variables = {
      SNAPSHOT_BUCKET          = var.snapshot_bucket_name
      DB_CREDENTIALS_PARAMETER = var.db_credentials_parameter_name
      LOCK_TIMEOUT             = "10s"
      ALLOW_REPAIR             = "false"
    }
  }

  vpc_config {
    subnet_ids         = local.private_subnet_ids
    security_group_ids = [aws_security_group.db_migrate.id]
  }

  depends_on = [
    aws_cloudwatch_log_group.db_migrate,
    aws_iam_role_policy_attachment.vpc_access,
    aws_iam_role_policy.db_migrate,
  ]

  tags = merge(module.iac_tags.tags, module.owner_tags.tags)
}

# Only this environment's Concourse role may invoke the function.
resource "aws_lambda_permission" "concourse" {
  statement_id  = "AllowConcourseInvoke"
  action        = "lambda:InvokeFunction"
  function_name = aws_lambda_function.db_migrate.function_name
  principal     = var.concourse_role_arn
}

# ---------------------------------------------------------------------------
# Alarm
# ---------------------------------------------------------------------------

resource "aws_cloudwatch_metric_alarm" "errors" {
  alarm_name          = "${local.function_name}-errors"
  alarm_description   = "db-migrate Lambda reported errors"
  namespace           = "AWS/Lambda"
  metric_name         = "Errors"
  statistic           = "Sum"
  period              = 60
  evaluation_periods  = 1
  threshold           = 0
  comparison_operator = "GreaterThanThreshold"
  treat_missing_data  = "notBreaching"

  dimensions = {
    FunctionName = aws_lambda_function.db_migrate.function_name
  }

  alarm_actions = [var.alarm_sns_topic_arn]

  tags = merge(module.iac_tags.tags, module.owner_tags.tags)
}

# ---------------------------------------------------------------------------
# Tags
# ---------------------------------------------------------------------------

module "iac_tags" {
  source = "git@github.com:companieshouse/terraform-modules//aws/tagging/iac?ref=1.0.397"

  group           = "db-migrate"
  source_code_url = "https://github.com/companieshouse/address-lookup-api.git"
}

module "owner_tags" {
  source = "git@github.com:companieshouse/terraform-modules//aws/tagging/owner?ref=1.0.397"

  platform_owner = "development"
}
