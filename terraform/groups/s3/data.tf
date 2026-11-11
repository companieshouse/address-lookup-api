data "aws_caller_identity" "current" {}

data "aws_iam_policy_document" "bucket" {
  for_each = local.buckets

  statement {
    sid       = "DenyInsecureTransport"
    effect    = "Deny"
    actions   = ["s3:*"]
    resources = [aws_s3_bucket.this[each.key].arn, "${aws_s3_bucket.this[each.key].arn}/*"]

    principals {
      type        = "*"
      identifiers = ["*"]
    }

    condition {
      test     = "Bool"
      variable = "aws:SecureTransport"
      values   = ["false"]
    }
  }

  # Quarantine: only the Quarantine Lambda role writes ...
  dynamic "statement" {
    for_each = each.key == "quarantine" ? [1] : []

    content {
      sid       = "DenyWriteExceptQuarantineLambda"
      effect    = "Deny"
      actions   = local.quarantine_write_actions
      resources = ["${aws_s3_bucket.this[each.key].arn}/*"]

      principals {
        type        = "*"
        identifiers = ["*"]
      }

      condition {
        test     = "ArnNotEquals"
        variable = "aws:PrincipalArn"
        values   = [local.quarantine_lambda_role_arn]
      }
    }
  }

  # ... and nobody reads except the break-glass role (the Lambda cannot read back).
  dynamic "statement" {
    for_each = each.key == "quarantine" ? [1] : []

    content {
      sid       = "DenyReadExceptBreakGlass"
      effect    = "Deny"
      actions   = local.quarantine_read_actions
      resources = [aws_s3_bucket.this[each.key].arn, "${aws_s3_bucket.this[each.key].arn}/*"]

      principals {
        type        = "*"
        identifiers = ["*"]
      }

      condition {
        test     = "ArnNotEquals"
        variable = "aws:PrincipalArn"
        values   = [local.break_glass_role_arn]
      }
    }
  }
}
