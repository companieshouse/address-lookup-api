# Commands travel on a dedicated bus rather than the default one. Discovery
# emits DOWNLOAD, the scan reconciler emits DOWNLOAD or UNZIP, and each command
# is a wake-up only: the durable state that authorises work lives in S3, so a
# lost or duplicated event costs at most one wasted invocation.
resource "aws_cloudwatch_event_bus" "acquisition" {
  name = local.event_bus_name

  tags = merge(
    module.iac_tags.tags,
    module.owner_tags.tags,
    { Name = local.event_bus_name }
  )
}

# The handlers deliberately throw so that a failed asynchronous invocation is
# retried and then parked here rather than disappearing into the log group.
resource "aws_sqs_queue" "dead_letter" {
  name                      = "${var.environment}-${local.name_prefix}-acquisition-dlq"
  message_retention_seconds = 1209600
  sqs_managed_sse_enabled   = true

  tags = merge(
    module.iac_tags.tags,
    module.owner_tags.tags,
    { Name = "${var.environment}-${local.name_prefix}-acquisition-dlq" }
  )
}

resource "aws_sqs_queue_policy" "dead_letter" {
  queue_url = aws_sqs_queue.dead_letter.id
  policy    = data.aws_iam_policy_document.dead_letter_queue_policy.json
}

resource "aws_cloudwatch_metric_alarm" "dead_letter" {
  alarm_name        = "${var.environment}-${local.name_prefix}-acquisition-dlq-not-empty"
  alarm_description = "An address-lookup acquisition invocation exhausted its retries. Inspect the message and the function's log group; the pipeline is idempotent, so replaying is safe once the cause is fixed."

  namespace   = "AWS/SQS"
  metric_name = "ApproximateNumberOfMessagesVisible"
  statistic   = "Maximum"

  dimensions = {
    QueueName = aws_sqs_queue.dead_letter.name
  }

  comparison_operator = "GreaterThanThreshold"
  threshold           = 0
  period              = 300
  evaluation_periods  = 1
  treat_missing_data  = "notBreaching"

  tags = merge(module.iac_tags.tags, module.owner_tags.tags)
}
