data "aws_caller_identity" "current" {}

data "aws_iam_policy_document" "bucket" {
  statement {
    sid       = "DenyInsecureTransport"
    effect    = "Deny"
    actions   = ["s3:*"]
    resources = [aws_s3_bucket.this.arn, "${aws_s3_bucket.this.arn}/*"]

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

  statement {
    sid    = "DenyWriteExceptQuarantineLambda"
    effect = "Deny"
    actions = [
      "s3:PutObject*",
      "s3:DeleteObject*",
      "s3:AbortMultipartUpload",
      "s3:RestoreObject",
      "s3:ReplicateObject",
      "s3:ReplicateDelete",
      "s3:ObjectOwnerOverrideToBucketOwner",
    ]
    resources = ["${aws_s3_bucket.this.arn}/quarantine/*"]

    principals {
      type        = "*"
      identifiers = ["*"]
    }

    condition {
      test     = "ArnNotEquals"
      variable = "aws:PrincipalArn"
      values   = [aws_iam_role.lambda["quarantine"].arn]
    }
  }

  statement {
    sid       = "DenyReadExceptBreakGlass"
    effect    = "Deny"
    actions   = ["s3:GetObject*", "s3:ListMultipartUploadParts"]
    resources = ["${aws_s3_bucket.this.arn}/quarantine/*"]

    principals {
      type        = "*"
      identifiers = ["*"]
    }

    condition {
      test     = "ArnNotEquals"
      variable = "aws:PrincipalArn"
      values   = [var.break_glass_role_arn]
    }
  }

  # An unfiltered listing would expose Quarantine object names.
  statement {
    sid       = "DenyListingOutsideNonQuarantinePrefixes"
    effect    = "Deny"
    actions   = ["s3:ListBucket", "s3:ListBucketVersions", "s3:ListBucketMultipartUploads"]
    resources = [aws_s3_bucket.this.arn]

    principals {
      type        = "*"
      identifiers = ["*"]
    }

    condition {
      test     = "ArnNotEquals"
      variable = "aws:PrincipalArn"
      values   = [var.break_glass_role_arn]
    }

    condition {
      test     = "StringNotLike"
      variable = "s3:prefix"
      values   = ["control/*", "landing/*"]
    }
  }
}
