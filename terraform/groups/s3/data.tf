data "aws_caller_identity" "current" {}

data "aws_s3_bucket" "acquisition" {
  bucket = var.acquisition_bucket_name

  lifecycle {
    precondition {
      condition     = !var.shared_bucket || var.acquisition_root_prefix != ""
      error_message = "A shared bucket requires an environment-specific acquisition_root_prefix."
    }

    precondition {
      condition     = !var.shared_bucket || !var.manage_guardduty_plan
      error_message = "The shared bucket's owner must manage its single GuardDuty plan."
    }
  }
}

data "aws_kms_key" "acquisition" {
  key_id = local.kms_key_id

  lifecycle {
    precondition {
      condition     = !var.shared_bucket || (var.kms_key_id == null ? false : can(regex("^arn:[a-z0-9-]+:kms:[a-z0-9-]+:[0-9]{12}:key/.+$", var.kms_key_id)))
      error_message = "Set kms_key_id to the shared bucket's customer-managed KMS key ARN, not a bucket ARN or account-local alias."
    }
  }
}

data "aws_iam_policy_document" "download_storage" {
  statement {
    sid       = "ListAcquisitionStateAndDownloads"
    effect    = "Allow"
    actions   = ["s3:ListBucket"]
    resources = [data.aws_s3_bucket.acquisition.arn]

    condition {
      test     = "StringLike"
      variable = "s3:prefix"
      values   = ["${local.control_prefix}*", "${local.landing_prefix}*"]
    }
  }

  statement {
    sid     = "ReadWriteControlStateAndDownloads"
    effect  = "Allow"
    actions = ["s3:GetObject", "s3:GetObjectVersion", "s3:PutObject", "s3:AbortMultipartUpload"]
    resources = [
      "${data.aws_s3_bucket.acquisition.arn}/${local.control_prefix}*",
      "${data.aws_s3_bucket.acquisition.arn}/${local.landing_prefix}*",
    ]
  }

  statement {
    sid       = "EncryptDecryptAcquisitionObjects"
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
