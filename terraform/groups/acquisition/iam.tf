# One least-privilege policy per function. Each is scoped to the acquisition
# bucket, its own cursor objects in Control, its own feed prefixes in Landing,
# its own parameter and the environment KMS key. No s3:* and no Resource "*".
#
# Landing feed paths are <landing>/<feed>/<package>/<version>/..., so the
# packages, download and extraction receipts and next-version pointers all sit
# under the feed prefixes. Only the cursors live in Control.

locals {
  s3_via_service  = "s3.${var.aws_region}.amazonaws.com"
  ssm_via_service = "ssm.${var.aws_region}.amazonaws.com"

  log_group_arns = {
    for key, name in local.lambda_names :
    key => "arn:aws:logs:${var.aws_region}:${local.account_id}:log-group:/aws/lambda/${name}"
  }

  # Object actions each function may take on its feed prefixes. The bucket is
  # versioned and the code reads objects and GuardDuty tags by versionId, which
  # needs the *Version actions.
  feed_actions = {
    download   = ["s3:GetObject", "s3:GetObjectVersion", "s3:PutObject", "s3:AbortMultipartUpload"]
    extraction = ["s3:GetObject", "s3:GetObjectVersion", "s3:GetObjectTagging", "s3:GetObjectVersionTagging", "s3:PutObject", "s3:AbortMultipartUpload"]
    upload     = ["s3:GetObject", "s3:GetObjectVersion", "s3:GetObjectTagging", "s3:GetObjectVersionTagging", "s3:PutObject"]
  }
}

data "aws_iam_policy_document" "acquisition" {
  for_each = local.function_names

  statement {
    sid       = "ListOwnPrefixes"
    effect    = "Allow"
    actions   = ["s3:ListBucket"]
    resources = [local.bucket_arn]

    condition {
      test     = "StringLike"
      variable = "s3:prefix"
      values = concat(
        ["${local.cursor_prefix}*"],
        [for prefix in local.feed_prefixes[each.key] : "${prefix}*"],
      )
    }
  }

  statement {
    sid       = "ReadWriteOwnCursor"
    effect    = "Allow"
    actions   = ["s3:GetObject", "s3:GetObjectVersion", "s3:PutObject"]
    resources = ["${local.bucket_arn}/${local.cursor_prefix}${local.cursor_patterns[each.key]}"]
  }

  dynamic "statement" {
    for_each = length(local.cursor_denied_patterns[each.key]) > 0 ? [each.key] : []

    content {
      sid       = "NotOtherCursors"
      effect    = "Deny"
      actions   = ["s3:GetObject", "s3:GetObjectVersion", "s3:PutObject"]
      resources = [for pattern in local.cursor_denied_patterns[each.key] : "${local.bucket_arn}/${local.cursor_prefix}${pattern}"]
    }
  }

  statement {
    sid       = "FeedObjects"
    effect    = "Allow"
    actions   = local.feed_actions[each.key]
    resources = [for prefix in local.feed_prefixes[each.key] : "${local.bucket_arn}/${prefix}*"]
  }

  statement {
    sid       = "BucketKeyThroughS3"
    effect    = "Allow"
    actions   = ["kms:Decrypt", "kms:GenerateDataKey"]
    resources = [data.aws_kms_key.environment.arn]

    condition {
      test     = "StringEquals"
      variable = "kms:ViaService"
      values   = [local.s3_via_service]
    }
  }

  dynamic "statement" {
    for_each = each.key == "upload" ? [] : [each.key]

    content {
      sid       = "WriteOwnLogs"
      effect    = "Allow"
      actions   = ["logs:CreateLogStream", "logs:PutLogEvents"]
      resources = ["${local.log_group_arns[each.key]}:*"]
    }
  }

  dynamic "statement" {
    for_each = contains(["download", "upload"], each.key) ? [each.key] : []

    content {
      sid       = "ReadOwnParameter"
      effect    = "Allow"
      actions   = ["ssm:GetParameter"]
      resources = [each.key == "download" ? local.os_api_key_parameter_arn : local.upload_credentials_parameter_arn]
    }
  }

  dynamic "statement" {
    for_each = contains(["download", "upload"], each.key) ? [each.key] : []

    content {
      sid       = "DecryptOwnParameter"
      effect    = "Allow"
      actions   = ["kms:Decrypt"]
      resources = [data.aws_kms_key.environment.arn]

      condition {
        test     = "StringEquals"
        variable = "kms:ViaService"
        values   = [local.ssm_via_service]
      }
    }
  }
}

data "aws_iam_policy_document" "lambda_assume_role" {
  statement {
    effect  = "Allow"
    actions = ["sts:AssumeRole"]

    principals {
      type        = "Service"
      identifiers = ["lambda.amazonaws.com"]
    }

    condition {
      test     = "StringEquals"
      variable = "aws:SourceAccount"
      values   = [local.account_id]
    }
  }
}
