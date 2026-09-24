# The source bucket is the red zone. Anything written here is unverified until
# GuardDuty tags it NO_THREATS_FOUND.
resource "aws_s3_bucket" "source" {
  bucket = local.source_bucket_name

  tags = merge(
    module.iac_tags.tags,
    module.owner_tags.tags,
    { Name = local.source_bucket_name }
  )
}

# The scanned bucket is the clean zone. The import Lambda reads nothing else.
resource "aws_s3_bucket" "scanned" {
  bucket = local.scanned_bucket_name

  tags = merge(
    module.iac_tags.tags,
    module.owner_tags.tags,
    { Name = local.scanned_bucket_name }
  )
}

# Versioning is not optional. ReleaseStore records the object version in every
# receipt and later reads pin that version, so that content certified clean
# cannot be swapped for something else before it is used.
resource "aws_s3_bucket_versioning" "source" {
  bucket = aws_s3_bucket.source.id

  versioning_configuration {
    status = "Enabled"
  }
}

resource "aws_s3_bucket_versioning" "scanned" {
  bucket = aws_s3_bucket.scanned.id

  versioning_configuration {
    status = "Enabled"
  }
}

# Encrypted with the customer managed key in kms.tf. Bucket keys cut the number
# of KMS requests made while large objects are streamed in parts.
resource "aws_s3_bucket_server_side_encryption_configuration" "source" {
  bucket = aws_s3_bucket.source.id

  rule {
    apply_server_side_encryption_by_default {
      sse_algorithm     = "aws:kms"
      kms_master_key_id = module.acquisition_kms.key_arn
    }
    bucket_key_enabled = true
  }
}

resource "aws_s3_bucket_server_side_encryption_configuration" "scanned" {
  bucket = aws_s3_bucket.scanned.id

  rule {
    apply_server_side_encryption_by_default {
      sse_algorithm     = "aws:kms"
      kms_master_key_id = module.acquisition_kms.key_arn
    }
    bucket_key_enabled = true
  }
}

resource "aws_s3_bucket_public_access_block" "source" {
  bucket = aws_s3_bucket.source.id

  block_public_acls       = true
  block_public_policy     = true
  ignore_public_acls      = true
  restrict_public_buckets = true
}

resource "aws_s3_bucket_public_access_block" "scanned" {
  bucket = aws_s3_bucket.scanned.id

  block_public_acls       = true
  block_public_policy     = true
  ignore_public_acls      = true
  restrict_public_buckets = true
}

resource "aws_s3_bucket_ownership_controls" "source" {
  bucket = aws_s3_bucket.source.id

  rule {
    object_ownership = "BucketOwnerEnforced"
  }
}

resource "aws_s3_bucket_ownership_controls" "scanned" {
  bucket = aws_s3_bucket.scanned.id

  rule {
    object_ownership = "BucketOwnerEnforced"
  }
}

resource "aws_s3_bucket_policy" "source_tls_only" {
  bucket = aws_s3_bucket.source.id
  policy = data.aws_iam_policy_document.source_bucket_policy.json

  depends_on = [aws_s3_bucket_public_access_block.source]
}

resource "aws_s3_bucket_policy" "scanned_tls_only" {
  bucket = aws_s3_bucket.scanned.id
  policy = data.aws_iam_policy_document.scanned_bucket_policy.json

  depends_on = [aws_s3_bucket_public_access_block.scanned]
}

# Bulk content is disposable once a release has been imported; the small
# control objects that make the pipeline idempotent are kept.
resource "aws_s3_bucket_lifecycle_configuration" "source" {
  bucket = aws_s3_bucket.source.id

  rule {
    id     = "expire-quarantine"
    status = "Enabled"

    filter {
      prefix = "quarantine/"
    }

    expiration {
      days = var.source_bucket_expiration_days
    }

    noncurrent_version_expiration {
      noncurrent_days = var.source_bucket_expiration_days
    }
  }

  rule {
    id     = "abort-incomplete-multipart-uploads"
    status = "Enabled"

    filter {}

    abort_incomplete_multipart_upload {
      days_after_initiation = 7
    }
  }
}

resource "aws_s3_bucket_lifecycle_configuration" "scanned" {
  bucket = aws_s3_bucket.scanned.id

  rule {
    id     = "expire-extracted-csv"
    status = "Enabled"

    filter {
      prefix = "quarantine/csv/"
    }

    expiration {
      days = var.scanned_bucket_expiration_days
    }

    noncurrent_version_expiration {
      noncurrent_days = var.scanned_bucket_expiration_days
    }
  }

  rule {
    id     = "abort-incomplete-multipart-uploads"
    status = "Enabled"

    filter {}

    abort_incomplete_multipart_upload {
      days_after_initiation = 7
    }
  }
}

# Server access logs go to the account's shared access logging bucket, created
# by aws-common-infrastructure-terraform, as for every other bucket in the org.
module "source_s3_access_logging" {
  source = "git@github.com:companieshouse/terraform-modules//aws/s3_access_logging?ref=1.0.434"

  aws_account           = var.aws_account
  aws_region            = var.aws_region
  source_s3_bucket_name = aws_s3_bucket.source.id
}

module "scanned_s3_access_logging" {
  source = "git@github.com:companieshouse/terraform-modules//aws/s3_access_logging?ref=1.0.434"

  aws_account           = var.aws_account
  aws_region            = var.aws_region
  source_s3_bucket_name = aws_s3_bucket.scanned.id
}
