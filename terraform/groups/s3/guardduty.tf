variable "guardduty_scan_prefixes" {
  type        = list(string)
  description = "Optional literal scan prefixes within the configured landing prefix"
  default     = null

  validation {
    condition     = var.guardduty_scan_prefixes == null ? true : length(var.guardduty_scan_prefixes) > 0 && length(var.guardduty_scan_prefixes) <= 5 && alltrue([for prefix in var.guardduty_scan_prefixes : !strcontains(prefix, "*") && !strcontains(prefix, "?")])
    error_message = "Supply one to five literal GuardDuty scan prefixes without IAM wildcards."
  }
}

locals {
  guardduty_service_principal = "malware-protection-plan.guardduty.amazonaws.com"
  guardduty_role_name         = "${local.bucket_prefix}-guardduty-malware-scan"
  landing_bucket_arn          = data.aws_s3_bucket.acquisition.arn
  guardduty_rule_arn          = "arn:aws:events:${var.aws_region}:${local.account_id}:rule/DO-NOT-DELETE-AmazonGuardDutyMalwareProtectionS3*"
  guardduty_scan_prefixes     = var.guardduty_scan_prefixes != null ? var.guardduty_scan_prefixes : [local.landing_prefix]
  guardduty_object_arns       = [for prefix in local.guardduty_scan_prefixes : "${local.landing_bucket_arn}/${prefix}*"]
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

  statement {
    sid       = "DecryptLandingObjects"
    effect    = "Allow"
    actions   = ["kms:Decrypt", "kms:GenerateDataKey"]
    resources = [data.aws_kms_key.acquisition.arn]

    condition {
      test     = "StringEquals"
      variable = "kms:ViaService"
      values   = ["s3.${var.aws_region}.amazonaws.com"]
    }
  }
}

resource "aws_iam_role" "guardduty" {
  count              = var.manage_guardduty_plan ? 1 : 0
  name               = local.guardduty_role_name
  assume_role_policy = data.aws_iam_policy_document.guardduty_assume_role.json

  tags = merge(module.iac_tags.tags, module.owner_tags.tags)

  lifecycle {
    prevent_destroy = true
  }
}

resource "aws_iam_role_policy" "guardduty" {
  count  = var.manage_guardduty_plan ? 1 : 0
  name   = "malware-protection-landing"
  role   = aws_iam_role.guardduty[0].id
  policy = data.aws_iam_policy_document.guardduty.json

  lifecycle {
    prevent_destroy = true
  }
}

# Only landing/ is scanned; control/ in the same bucket is deliberately excluded.
resource "aws_guardduty_malware_protection_plan" "landing" {
  count = var.manage_guardduty_plan ? 1 : 0
  role  = aws_iam_role.guardduty[0].arn

  protected_resource {
    s3_bucket {
      bucket_name     = data.aws_s3_bucket.acquisition.id
      object_prefixes = local.guardduty_scan_prefixes
    }
  }

  # Lets downstream Lambdas re-check the GuardDutyMalwareScanStatus object tag.
  actions {
    tagging {
      status = "ENABLED"
    }
  }

  tags = merge(module.iac_tags.tags, module.owner_tags.tags)

  lifecycle {
    prevent_destroy = true

    precondition {
      condition     = alltrue([for prefix in local.guardduty_scan_prefixes : startswith(prefix, local.landing_prefix)])
      error_message = "GuardDuty scan prefixes must be restricted to the configured landing prefix."
    }
  }

  depends_on = [aws_iam_role_policy.guardduty]
}

output "guardduty_malware_protection_plan_id" {
  description = "The ID of the Landing bucket's GuardDuty Malware Protection plan"
  value       = try(aws_guardduty_malware_protection_plan.landing[0].id, null)
}

output "guardduty_role_arn" {
  description = "The ARN of the IAM role assumed by GuardDuty Malware Protection"
  value       = try(aws_iam_role.guardduty[0].arn, null)
}
