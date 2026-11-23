resource "aws_cloudwatch_metric_alarm" "errors" {
  for_each = local.lambda_names

  alarm_name        = "${each.value}-errors"
  alarm_description = "A ${each.value} invocation failed. Check the function's logs."

  namespace   = "AWS/Lambda"
  metric_name = "Errors"
  dimensions = {
    FunctionName = each.value
  }

  statistic           = "Sum"
  period              = 60
  evaluation_periods  = 1
  comparison_operator = "GreaterThanThreshold"
  threshold           = 0
  treat_missing_data  = "notBreaching"

  alarm_actions = [data.aws_sns_topic.alarms.arn]
  ok_actions    = [data.aws_sns_topic.alarms.arn]

  tags = local.tags

  depends_on = [aws_lambda_function.unattached, module.upload]
}
