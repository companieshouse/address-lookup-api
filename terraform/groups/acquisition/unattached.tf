# Download and Extraction run outside the VPC: Download needs egress to
# api.os.uk, and neither touches the database. They are plain resources, as in
# companieshouse/s3-av-scanner, because terraform-modules aws/lambda always
# attaches a VPC and grants logs and ssm:GetParameters on "*".

# The logs hold no secrets or address data. A CMK would need the shared
# environment-services-kms key policy to admit CloudWatch Logs, which this
# group does not own. The aws/lambda module's log group (Upload) has none either.
#trivy:ignore:AVD-AWS-0017
resource "aws_cloudwatch_log_group" "unattached" {
  for_each = local.unattached

  name              = "/aws/lambda/${local.lambda_names[each.key]}"
  retention_in_days = var.lambda_logs_retention_days

  tags = local.tags
}

resource "aws_iam_role" "unattached" {
  for_each = local.unattached

  name               = "${local.lambda_names[each.key]}-lambda-role"
  assume_role_policy = data.aws_iam_policy_document.lambda_assume_role.json

  tags = local.tags
}

resource "aws_iam_role_policy" "unattached" {
  for_each = local.unattached

  name   = "${local.lambda_names[each.key]}-policy"
  role   = aws_iam_role.unattached[each.key].id
  policy = data.aws_iam_policy_document.acquisition[each.key].json
}

# X-Ray Active tracing needs xray:PutTraceSegments on Resource "*", which the
# ticket rules out. The aws/lambda module also leaves tracing off by default.
#trivy:ignore:AVD-AWS-0066
resource "aws_lambda_function" "unattached" {
  for_each = local.unattached

  function_name = local.lambda_names[each.key]
  role          = aws_iam_role.unattached[each.key].arn
  runtime       = var.lambda_runtime
  handler       = each.value.handler

  s3_bucket = var.release_bucket_name
  s3_key    = each.value.artifact_key

  memory_size = each.value.memory_size
  timeout     = each.value.timeout

  ephemeral_storage {
    size = each.value.ephemeral_storage
  }

  # One run per function at a time: the cursor in Control is not locked. Both
  # functions handle the COU and the FULL feeds, chosen by the event.
  reserved_concurrent_executions = 1

  environment {
    variables = each.value.env_vars
  }

  logging_config {
    log_format = "Text"
    log_group  = aws_cloudwatch_log_group.unattached[each.key].name
  }

  tags = local.tags

  depends_on = [aws_iam_role_policy.unattached]
}

# No automatic retry: the next scheduled run resumes from the cursor.
resource "aws_lambda_function_event_invoke_config" "unattached" {
  for_each = local.unattached

  function_name          = aws_lambda_function.unattached[each.key].function_name
  maximum_retry_attempts = 0
}
