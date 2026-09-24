data "vault_generic_secret" "stack_secrets" {
  path = local.stack_secrets_path
}

data "vault_generic_secret" "service_secrets" {
  path = local.service_secrets_path
}

data "aws_caller_identity" "aws_identity" {}

data "aws_partition" "current" {}

data "aws_vpc" "vpc" {
  filter {
    name   = "tag:Name"
    values = [local.vpc_name]
  }
}

data "aws_subnets" "application" {
  filter {
    name   = "vpc-id"
    values = [data.aws_vpc.vpc.id]
  }

  filter {
    name   = "tag:Name"
    values = [local.application_subnet_pattern]
  }
}

# The aurora group in this repository owns the cluster. Looking it up by its
# well-known identifier keeps the two groups independently applyable without
# introducing a remote state dependency.
data "aws_rds_cluster" "aurora" {
  cluster_identifier = lower("${var.environment}-${local.database_service_name}")
}

data "aws_security_group" "aurora" {
  vpc_id = data.aws_vpc.vpc.id

  filter {
    name   = "group-name"
    values = [local.database_sg_name]
  }
}

# ---------------------------------------------------------------------------
# Bucket policies
# ---------------------------------------------------------------------------

data "aws_iam_policy_document" "source_bucket_policy" {
  statement {
    sid       = "DenyInsecureTransport"
    effect    = "Deny"
    actions   = ["s3:*"]
    resources = [aws_s3_bucket.source.arn, "${aws_s3_bucket.source.arn}/*"]

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
}

data "aws_iam_policy_document" "scanned_bucket_policy" {
  statement {
    sid       = "DenyInsecureTransport"
    effect    = "Deny"
    actions   = ["s3:*"]
    resources = [aws_s3_bucket.scanned.arn, "${aws_s3_bucket.scanned.arn}/*"]

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
}

data "aws_iam_policy_document" "dead_letter_queue_policy" {
  statement {
    sid       = "DenyInsecureTransport"
    effect    = "Deny"
    actions   = ["sqs:*"]
    resources = [aws_sqs_queue.dead_letter.arn]

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
}

# ---------------------------------------------------------------------------
# GuardDuty Malware Protection
# ---------------------------------------------------------------------------

data "aws_iam_policy_document" "guardduty_malware_protection_trust" {
  statement {
    sid     = "GuardDutyMalwareProtectionCanAssumeThisRole"
    effect  = "Allow"
    actions = ["sts:AssumeRole"]

    principals {
      type        = "Service"
      identifiers = ["malware-protection-plan.guardduty.amazonaws.com"]
    }

    condition {
      test     = "StringEquals"
      variable = "aws:SourceAccount"
      values   = [data.aws_caller_identity.aws_identity.account_id]
    }

    condition {
      test     = "ArnLike"
      variable = "aws:SourceArn"
      values   = ["arn:${data.aws_partition.current.partition}:guardduty:${var.aws_region}:${data.aws_caller_identity.aws_identity.account_id}:malware-protection-plan/*"]
    }
  }
}

# Follows the role policy template in the GuardDuty user guide, as does
# file-transfer-stack. Object access is limited to the quarantine/ prefixes the
# protection plans cover, plus the validation object GuardDuty writes when a
# plan is created.
data "aws_iam_policy_document" "guardduty_malware_protection" {
  statement {
    sid    = "AllowManagedRuleToSendS3EventsToGuardDuty"
    effect = "Allow"

    actions = [
      "events:PutRule",
      "events:DeleteRule",
      "events:PutTargets",
      "events:RemoveTargets"
    ]

    resources = [
      "arn:${data.aws_partition.current.partition}:events:${var.aws_region}:${data.aws_caller_identity.aws_identity.account_id}:rule/DO-NOT-DELETE-AmazonGuardDutyMalwareProtectionS3*"
    ]

    condition {
      test     = "StringEquals"
      variable = "events:ManagedBy"
      values   = ["malware-protection-plan.guardduty.amazonaws.com"]
    }
  }

  statement {
    sid    = "AllowGuardDutyToMonitorEventBridgeManagedRule"
    effect = "Allow"

    actions = [
      "events:DescribeRule",
      "events:ListTargetsByRule"
    ]

    resources = [
      "arn:${data.aws_partition.current.partition}:events:${var.aws_region}:${data.aws_caller_identity.aws_identity.account_id}:rule/DO-NOT-DELETE-AmazonGuardDutyMalwareProtectionS3*"
    ]
  }

  statement {
    sid    = "AllowEnableS3EventBridgeEvents"
    effect = "Allow"

    actions = [
      "s3:PutBucketNotification",
      "s3:GetBucketNotification"
    ]

    resources = [
      aws_s3_bucket.source.arn,
      aws_s3_bucket.scanned.arn
    ]
  }

  statement {
    sid    = "AllowCheckBucketOwnership"
    effect = "Allow"

    actions = ["s3:ListBucket"]

    resources = [
      aws_s3_bucket.source.arn,
      aws_s3_bucket.scanned.arn
    ]
  }

  statement {
    sid    = "AllowMalwareScan"
    effect = "Allow"

    actions = [
      "s3:GetObject",
      "s3:GetObjectVersion"
    ]

    resources = [
      "${aws_s3_bucket.source.arn}/quarantine/*",
      "${aws_s3_bucket.scanned.arn}/quarantine/*",
      "${aws_s3_bucket.source.arn}/malware-protection-resource-validation-object",
      "${aws_s3_bucket.scanned.arn}/malware-protection-resource-validation-object"
    ]
  }

  statement {
    sid    = "AllowPostScanTag"
    effect = "Allow"

    actions = [
      "s3:GetObjectTagging",
      "s3:GetObjectVersionTagging",
      "s3:PutObjectTagging",
      "s3:PutObjectVersionTagging"
    ]

    resources = [
      "${aws_s3_bucket.source.arn}/quarantine/*",
      "${aws_s3_bucket.scanned.arn}/quarantine/*",
      "${aws_s3_bucket.source.arn}/malware-protection-resource-validation-object",
      "${aws_s3_bucket.scanned.arn}/malware-protection-resource-validation-object"
    ]
  }

  statement {
    sid    = "AllowPutValidationObject"
    effect = "Allow"

    actions = ["s3:PutObject"]

    resources = [
      "${aws_s3_bucket.source.arn}/malware-protection-resource-validation-object",
      "${aws_s3_bucket.scanned.arn}/malware-protection-resource-validation-object"
    ]
  }

  statement {
    sid    = "AllowDecryptForMalwareScan"
    effect = "Allow"

    actions = [
      "kms:GenerateDataKey",
      "kms:Decrypt"
    ]

    resources = [module.acquisition_kms.key_arn]

    condition {
      test     = "StringLike"
      variable = "kms:ViaService"
      values   = ["s3.${var.aws_region}.amazonaws.com"]
    }
  }
}

# Every function reads or writes objects under the acquisition key. Access is
# only usable through S3, so the key cannot be used to decrypt anything else.
data "aws_iam_policy_document" "acquisition_kms" {
  statement {
    sid    = "UseAcquisitionKeyThroughS3"
    effect = "Allow"

    actions = [
      "kms:Decrypt",
      "kms:GenerateDataKey"
    ]

    resources = [module.acquisition_kms.key_arn]

    condition {
      test     = "StringEquals"
      variable = "kms:ViaService"
      values   = ["s3.${var.aws_region}.amazonaws.com"]
    }
  }
}

# ---------------------------------------------------------------------------
# Per function execution policies
#
# Each function is granted only the prefixes it writes and the prefixes it
# reads. ListBucket is granted at bucket scope because ReleaseStore probes for
# the existence of an exact key before reading it, rather than relying on the
# difference between a 404 and a 403.
# ---------------------------------------------------------------------------

data "aws_iam_policy_document" "discovery" {
  statement {
    sid       = "ListSourceBucket"
    effect    = "Allow"
    actions   = ["s3:ListBucket"]
    resources = [aws_s3_bucket.source.arn]
  }

  statement {
    sid    = "ReadAndWriteAcquisitionPlans"
    effect = "Allow"

    actions = [
      "s3:GetObject",
      "s3:PutObject"
    ]

    resources = ["${aws_s3_bucket.source.arn}/acquisitions/*"]
  }

  statement {
    sid       = "IssueAcquisitionCommands"
    effect    = "Allow"
    actions   = ["events:PutEvents"]
    resources = [aws_cloudwatch_event_bus.acquisition.arn]
  }
}

data "aws_iam_policy_document" "download" {
  statement {
    sid       = "ListSourceBucket"
    effect    = "Allow"
    actions   = ["s3:ListBucket"]
    resources = [aws_s3_bucket.source.arn]
  }

  statement {
    sid    = "ReadAcquisitionPlansAndReceipts"
    effect = "Allow"

    actions = ["s3:GetObject"]

    resources = [
      "${aws_s3_bucket.source.arn}/acquisitions/*",
      "${aws_s3_bucket.source.arn}/control/*"
    ]
  }

  statement {
    sid    = "WriteQuarantinedZipsAndReceipts"
    effect = "Allow"

    actions = [
      "s3:PutObject",
      "s3:AbortMultipartUpload",
      "s3:ListMultipartUploadParts"
    ]

    resources = [
      "${aws_s3_bucket.source.arn}/quarantine/zips/*",
      "${aws_s3_bucket.source.arn}/control/*"
    ]
  }
}

data "aws_iam_policy_document" "scan" {
  statement {
    sid       = "ListBothBuckets"
    effect    = "Allow"
    actions   = ["s3:ListBucket"]
    resources = [aws_s3_bucket.source.arn, aws_s3_bucket.scanned.arn]
  }

  statement {
    sid    = "ReadPlansReceiptsAndScanEvidence"
    effect = "Allow"

    actions = [
      "s3:GetObject",
      "s3:GetObjectVersion",
      "s3:GetObjectTagging",
      "s3:GetObjectVersionTagging"
    ]

    resources = [
      "${aws_s3_bucket.source.arn}/acquisitions/*",
      "${aws_s3_bucket.source.arn}/control/*",
      "${aws_s3_bucket.source.arn}/quarantine/*",
      "${aws_s3_bucket.source.arn}/reconciliation/*",
      "${aws_s3_bucket.scanned.arn}/quarantine/*"
    ]
  }

  statement {
    sid       = "MaintainReconciliationCursor"
    effect    = "Allow"
    actions   = ["s3:PutObject"]
    resources = ["${aws_s3_bucket.source.arn}/reconciliation/*"]
  }

  statement {
    sid    = "PromoteCertifiedContent"
    effect = "Allow"

    actions = ["s3:PutObject"]

    resources = [
      "${aws_s3_bucket.scanned.arn}/manifests/*",
      "${aws_s3_bucket.scanned.arn}/ready/*"
    ]
  }

  statement {
    sid       = "ReissueOutstandingCommands"
    effect    = "Allow"
    actions   = ["events:PutEvents"]
    resources = [aws_cloudwatch_event_bus.acquisition.arn]
  }
}

data "aws_iam_policy_document" "unzip" {
  statement {
    sid       = "ListBothBuckets"
    effect    = "Allow"
    actions   = ["s3:ListBucket"]
    resources = [aws_s3_bucket.source.arn, aws_s3_bucket.scanned.arn]
  }

  statement {
    sid    = "ReadTheExactCertifiedZip"
    effect = "Allow"

    actions = [
      "s3:GetObject",
      "s3:GetObjectVersion",
      "s3:GetObjectTagging",
      "s3:GetObjectVersionTagging"
    ]

    resources = [
      "${aws_s3_bucket.source.arn}/acquisitions/*",
      "${aws_s3_bucket.source.arn}/control/*",
      "${aws_s3_bucket.source.arn}/quarantine/zips/*"
    ]
  }

  statement {
    sid       = "WriteExtractReceipts"
    effect    = "Allow"
    actions   = ["s3:PutObject"]
    resources = ["${aws_s3_bucket.source.arn}/control/*"]
  }

  statement {
    sid    = "StreamExtractedCsvOutOfTheRedZone"
    effect = "Allow"

    actions = [
      "s3:PutObject",
      "s3:AbortMultipartUpload",
      "s3:ListMultipartUploadParts"
    ]

    resources = ["${aws_s3_bucket.scanned.arn}/quarantine/csv/*"]
  }
}

data "aws_iam_policy_document" "import" {
  statement {
    sid       = "ListScannedBucket"
    effect    = "Allow"
    actions   = ["s3:ListBucket"]
    resources = [aws_s3_bucket.scanned.arn]
  }

  statement {
    sid    = "ReadCertifiedReleaseContent"
    effect = "Allow"

    actions = [
      "s3:GetObject",
      "s3:GetObjectVersion",
      "s3:GetObjectTagging",
      "s3:GetObjectVersionTagging"
    ]

    resources = [
      "${aws_s3_bucket.scanned.arn}/manifests/*",
      "${aws_s3_bucket.scanned.arn}/ready/*",
      "${aws_s3_bucket.scanned.arn}/quarantine/csv/*",
      "${aws_s3_bucket.scanned.arn}/reconciliation/*"
    ]
  }

  statement {
    sid       = "MaintainReconciliationCursor"
    effect    = "Allow"
    actions   = ["s3:PutObject"]
    resources = ["${aws_s3_bucket.scanned.arn}/reconciliation/*"]
  }
}
