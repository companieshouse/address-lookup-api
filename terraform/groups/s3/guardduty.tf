# Depends on the s3 group from ALS-103 (aws_s3_bucket.this, local.account_id, local.bucket_prefix, var.aws_region).

variable "guardduty_scan_prefixes" {
  type        = list(string)
  description = "Prefixes within landing/ scanned by GuardDuty; control/ must never be scanned"
  default     = ["landing/"]

  validation {
    condition     = length(var.guardduty_scan_prefixes) > 0 && alltrue([for prefix in var.guardduty_scan_prefixes : startswith(prefix, "landing/")])
    error_message = "GuardDuty scan prefixes must be restricted to landing/."
  }
}

variable "landing_kms_key_arn" {
  type        = string
  description = "ARN of the CMK encrypting the Landing bucket; null while the bucket uses SSE-S3"
  default     = null
}

locals {
  guardduty_service_principal = "malware-protection-plan.guardduty.amazonaws.com"
  guardduty_role_name         = "${local.bucket_prefix}-guardduty-malware-scan"
  landing_bucket_arn          = aws_s3_bucket.this["landing"].arn
  guardduty_rule_arn          = "arn:aws:events:${var.aws_region}:${local.account_id}:rule/DO-NOT-DELETE-AmazonGuardDutyMalwareProtectionS3*"
  guardduty_object_arns       = [for prefix in var.guardduty_scan_prefixes : "${local.landing_bucket_arn}/${prefix}*"]
}

data "aws_iam_policy_document" "guardduty_assume_role" {
  statement {
    sid     = "GuardDutyMalwareProtectionAssumeRole"
    effect  = "Allow"
    actions = ["sts:AssumeRole"]

    principals {
      type        = "Service"
      identifiers = [local.guardduty_service_principal]
    }

    condition {
      test     = "StringEquals"
      variable = "aws:SourceAccount"
      values   = [local.account_id]
    }

    condition {
      test     = "ArnLike"
      variable = "aws:SourceArn"
      values   = ["arn:aws:guardduty:${var.aws_region}:${local.account_id}:malware-protection-plan/*"]
    }
  }
}

data "aws_iam_policy_document" "guardduty" {
  statement {
    sid       = "ManageGuardDutyEventBridgeRule"
    effect    = "Allow"
    actions   = ["events:PutRule", "events:DeleteRule", "events:PutTargets", "events:RemoveTargets"]
    resources = [local.guardduty_rule_arn]

    condition {
      test     = "StringLike"
      variable = "events:ManagedBy"
      values   = [local.guardduty_service_principal]
    }
  }

  statement {
    sid       = "MonitorGuardDutyEventBridgeRule"
    effect    = "Allow"
    actions   = ["events:DescribeRule", "events:ListTargetsByRule"]
    resources = [local.guardduty_rule_arn]
  }

  statement {
    sid       = "EnableS3EventBridgeEvents"
    effect    = "Allow"
    actions   = ["s3:PutBucketNotification", "s3:GetBucketNotification"]
    resources = [local.landing_bucket_arn]
  }

  statement {
    sid       = "CheckBucketOwnership"
    effect    = "Allow"
    actions   = ["s3:ListBucket"]
    resources = [local.landing_bucket_arn]
  }

  statement {
    sid       = "PutValidationObject"
    effect    = "Allow"
    actions   = ["s3:PutObject"]
    resources = ["${local.landing_bucket_arn}/malware-protection-resource-validation-object"]
  }

  statement {
    sid       = "ScanObjects"
    effect    = "Allow"
    actions   = ["s3:GetObject", "s3:GetObjectVersion"]
    resources = local.guardduty_object_arns
  }

  statement {
    sid    = "TagScannedObjects"
    effect = "Allow"
    actions = [
      "s3:GetObjectTagging",
      "s3:GetObjectVersionTagging",
      "s3:PutObjectTagging",
      "s3:PutObjectVersionTagging",
    ]
    resources = local.guardduty_object_arns
  }

  dynamic "statement" {
    for_each = var.landing_kms_key_arn == null ? [] : [var.landing_kms_key_arn]

    content {
      sid       = "DecryptLandingObjects"
      effect    = "Allow"
      actions   = ["kms:Decrypt", "kms:GenerateDataKey"]
      resources = [statement.value]

      condition {
        test     = "StringLike"
        variable = "kms:ViaService"
        values   = ["s3.${var.aws_region}.amazonaws.com"]
      }
    }
  }
}

resource "aws_iam_role" "guardduty" {
  name               = local.guardduty_role_name
  assume_role_policy = data.aws_iam_policy_document.guardduty_assume_role.json

  tags = merge(module.iac_tags.tags, module.owner_tags.tags)
}

resource "aws_iam_role_policy" "guardduty" {
  name   = "malware-protection-landing"
  role   = aws_iam_role.guardduty.id
  policy = data.aws_iam_policy_document.guardduty.json
}

# Only landing/ is scanned; control/ in the same bucket is deliberately excluded.
resource "aws_guardduty_malware_protection_plan" "landing" {
  role = aws_iam_role.guardduty.arn

  protected_resource {
    s3_bucket {
      bucket_name     = aws_s3_bucket.this["landing"].id
      object_prefixes = var.guardduty_scan_prefixes
    }
  }

  # Lets downstream Lambdas re-check the GuardDutyMalwareScanStatus object tag.
  actions {
    tagging {
      status = "ENABLED"
    }
  }

  tags = merge(module.iac_tags.tags, module.owner_tags.tags)

  depends_on = [aws_iam_role_policy.guardduty]
}

output "guardduty_malware_protection_plan_id" {
  description = "The ID of the Landing bucket's GuardDuty Malware Protection plan"
  value       = aws_guardduty_malware_protection_plan.landing.id
}

output "guardduty_role_arn" {
  description = "The ARN of the IAM role assumed by GuardDuty Malware Protection"
  value       = aws_iam_role.guardduty.arn
}
