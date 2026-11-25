locals {
  reconciliation_targets = {
    for name, arn in var.reconciliation_lambda_arns : name => arn
    if arn != ""
  }
}

resource "aws_cloudwatch_event_rule" "reconciliation" {
  for_each = local.reconciliation_targets

  name                = "${local.bucket_prefix}-${each.key}-reconciliation"
  description         = "Periodically reconcile Landing objects using their scan tags"
  schedule_expression = var.reconciliation_schedule
  tags                = merge(module.iac_tags.tags, module.owner_tags.tags)
}

resource "aws_cloudwatch_event_target" "reconciliation" {
  for_each = local.reconciliation_targets

  rule = aws_cloudwatch_event_rule.reconciliation[each.key].name
  arn  = each.value
}

resource "aws_lambda_permission" "reconciliation" {
  for_each = local.reconciliation_targets

  statement_id  = "AllowScheduledReconciliation"
  action        = "lambda:InvokeFunction"
  function_name = each.value
  principal     = "events.amazonaws.com"
  source_arn    = aws_cloudwatch_event_rule.reconciliation[each.key].arn
}
