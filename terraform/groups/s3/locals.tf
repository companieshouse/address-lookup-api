locals {
  bucket_prefix = "address-lookup-${var.environment}"

  # Control is separate so that it is never scanned.
  buckets = {
    control = {
      name = "${local.bucket_prefix}-control"
    }
    landing = {
      name = "${local.bucket_prefix}-landing"
    }
    quarantine = {
      name = "${local.bucket_prefix}-quarantine"
    }
  }

  account_id = data.aws_caller_identity.current.account_id

  quarantine_lambda_role_arn = "arn:aws:iam::${local.account_id}:role/${var.quarantine_lambda_role_name}"
  break_glass_role_arn       = "arn:aws:iam::${local.account_id}:role/${var.break_glass_role_name}"

  quarantine_read_actions = [
    "s3:GetObject",
    "s3:GetObjectVersion",
    "s3:GetObjectAttributes",
    "s3:GetObjectTagging",
    "s3:GetObjectVersionTagging",
    "s3:ListBucket",
    "s3:ListBucketVersions",
  ]

  quarantine_write_actions = [
    "s3:PutObject",
    "s3:PutObjectTagging",
    "s3:PutObjectVersionTagging",
    "s3:DeleteObject",
    "s3:DeleteObjectVersion",
    "s3:AbortMultipartUpload",
  ]
}
