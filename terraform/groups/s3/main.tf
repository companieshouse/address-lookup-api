terraform {
  backend "s3" {
  }

  required_version = ">= 1.3, < 2.0"

  required_providers {
    aws = {
      source  = "hashicorp/aws"
      version = ">= 6.0, < 7.0"
    }

    vault = {
      source  = "hashicorp/vault"
      version = ">= 5.0, < 6.0"
    }
  }
}

provider "aws" {
  region = var.aws_region
}

resource "aws_s3_bucket" "this" {
  for_each = local.buckets

  bucket = each.value.name

  tags = merge(module.iac_tags.tags, module.owner_tags.tags, { Purpose = each.key })
}

# Extraction and Upload pin to object versionId, so versioning is required.
resource "aws_s3_bucket_versioning" "this" {
  for_each = local.buckets

  bucket = aws_s3_bucket.this[each.key].id

  versioning_configuration {
    status = "Enabled"
  }
}

resource "aws_s3_bucket_public_access_block" "this" {
  for_each = local.buckets

  bucket = aws_s3_bucket.this[each.key].id

  block_public_acls       = true
  block_public_policy     = true
  ignore_public_acls      = true
  restrict_public_buckets = true
}

resource "aws_s3_bucket_ownership_controls" "this" {
  for_each = local.buckets

  bucket = aws_s3_bucket.this[each.key].id

  rule {
    object_ownership = "BucketOwnerEnforced"
  }
}

resource "aws_s3_bucket_server_side_encryption_configuration" "this" {
  for_each = local.buckets

  bucket = aws_s3_bucket.this[each.key].id

  rule {
    apply_server_side_encryption_by_default {
      sse_algorithm = "AES256"
    }
  }
}

resource "aws_s3_bucket_policy" "this" {
  for_each = local.buckets

  bucket = aws_s3_bucket.this[each.key].id
  policy = data.aws_iam_policy_document.bucket[each.key].json

  depends_on = [aws_s3_bucket_public_access_block.this]
}

# Landing: raw/ and extracted/ expire after var.landing_retention_days.
resource "aws_s3_bucket_lifecycle_configuration" "landing" {
  bucket = aws_s3_bucket.this["landing"].id

  rule {
    id     = "expire-raw"
    status = "Enabled"

    filter {
      prefix = "raw/"
    }

    expiration {
      days = var.landing_retention_days
    }

    noncurrent_version_expiration {
      noncurrent_days = var.noncurrent_retention_days
    }

    abort_incomplete_multipart_upload {
      days_after_initiation = 7
    }
  }

  rule {
    id     = "expire-extracted"
    status = "Enabled"

    filter {
      prefix = "extracted/"
    }

    expiration {
      days = var.landing_retention_days
    }

    noncurrent_version_expiration {
      noncurrent_days = var.noncurrent_retention_days
    }

    abort_incomplete_multipart_upload {
      days_after_initiation = 7
    }
  }

  depends_on = [aws_s3_bucket_versioning.this]
}

# Control: sources/acquisitions/ is kept longer for audit.
resource "aws_s3_bucket_lifecycle_configuration" "control" {
  bucket = aws_s3_bucket.this["control"].id

  rule {
    id     = "expire-acquisition-sources"
    status = "Enabled"

    filter {
      prefix = "sources/acquisitions/"
    }

    expiration {
      days = var.audit_retention_days
    }

    noncurrent_version_expiration {
      noncurrent_days = var.audit_retention_days
    }

    abort_incomplete_multipart_upload {
      days_after_initiation = 7
    }
  }

  depends_on = [aws_s3_bucket_versioning.this]
}

# Quarantine holds objects awaiting scan or failed scan; expire them like landing data.
resource "aws_s3_bucket_lifecycle_configuration" "quarantine" {
  bucket = aws_s3_bucket.this["quarantine"].id

  rule {
    id     = "expire-quarantine"
    status = "Enabled"

    filter {}

    expiration {
      days = var.landing_retention_days
    }

    noncurrent_version_expiration {
      noncurrent_days = var.noncurrent_retention_days
    }

    abort_incomplete_multipart_upload {
      days_after_initiation = 7
    }
  }

  depends_on = [aws_s3_bucket_versioning.this]
}

module "iac_tags" {
  source = "git@github.com:companieshouse/terraform-modules//aws/tagging/iac?ref=1.0.442"

  group           = "s3"
  source_code_url = "https://github.com/companieshouse/address-lookup-api.git"
}

module "owner_tags" {
  source = "git@github.com:companieshouse/terraform-modules//aws/tagging/owner?ref=1.0.442"

  platform_owner = "development"
}
