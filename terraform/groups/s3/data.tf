data "aws_caller_identity" "current" {}

data "aws_kms_key" "acquisition" {
  key_id = local.kms_key_id
}

data "aws_iam_policy_document" "bucket" {
  statement {
    sid       = "DenyInsecureTransport"
    effect    = "Deny"
    actions   = ["s3:*"]
    resources = [aws_s3_bucket.acquisition.arn, "${aws_s3_bucket.acquisition.arn}/*"]

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

data "aws_iam_policy_document" "download_storage" {
  statement {
    sid       = "ListAcquisitionStateAndDownloads"
    effect    = "Allow"
    actions   = ["s3:ListBucket"]
    resources = [aws_s3_bucket.acquisition.arn]

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
      "${aws_s3_bucket.acquisition.arn}/${local.control_prefix}*",
      "${aws_s3_bucket.acquisition.arn}/${local.landing_prefix}*",
    ]
  }

  statement {
    sid       = "ReadLandingScanTags"
    effect    = "Allow"
    actions   = ["s3:GetObjectTagging", "s3:GetObjectVersionTagging"]
    resources = ["${aws_s3_bucket.acquisition.arn}/${local.landing_prefix}*"]
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
