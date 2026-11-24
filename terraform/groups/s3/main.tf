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

resource "aws_s3_bucket" "acquisition" {
  bucket = "${local.bucket_prefix}-${local.account_id}-landing"
  tags   = merge(module.iac_tags.tags, module.owner_tags.tags, { Purpose = "landing" })

  lifecycle {
    prevent_destroy = true
  }
}

resource "aws_s3_bucket_logging" "acquisition" {
  bucket        = aws_s3_bucket.acquisition.id
  target_bucket = "${trimsuffix(var.aws_profile, "-${var.aws_region}")}-${var.aws_region}-s3-access-logs.ch.gov.uk"
  target_prefix = "${local.account_id}/${var.aws_region}/${aws_s3_bucket.acquisition.id}/"
}

resource "aws_s3_bucket_versioning" "acquisition" {
  bucket = aws_s3_bucket.acquisition.id

  versioning_configuration {
    status = "Enabled"
  }
}

resource "aws_s3_bucket_public_access_block" "acquisition" {
  bucket = aws_s3_bucket.acquisition.id

  block_public_acls       = true
  block_public_policy     = true
  ignore_public_acls      = true
  restrict_public_buckets = true
}

resource "aws_s3_bucket_ownership_controls" "acquisition" {
  bucket = aws_s3_bucket.acquisition.id

  rule {
    object_ownership = "BucketOwnerEnforced"
  }
}

resource "aws_s3_bucket_server_side_encryption_configuration" "acquisition" {
  bucket = aws_s3_bucket.acquisition.id

  rule {
    apply_server_side_encryption_by_default {
      sse_algorithm     = "aws:kms"
      kms_master_key_id = data.aws_kms_key.acquisition.arn
    }
  }
}

resource "aws_s3_bucket_policy" "acquisition" {
  bucket = aws_s3_bucket.acquisition.id
  policy = data.aws_iam_policy_document.bucket.json

  depends_on = [aws_s3_bucket_public_access_block.acquisition]
}

resource "aws_s3_bucket_lifecycle_configuration" "acquisition" {
  bucket = aws_s3_bucket.acquisition.id

  rule {
    id     = "expire-landing"
    status = "Enabled"

    filter {
      prefix = local.landing_prefix
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
    id     = "expire-control-history"
    status = "Enabled"

    filter {
      prefix = local.control_prefix
    }

    noncurrent_version_expiration {
      noncurrent_days = var.audit_retention_days
    }

    abort_incomplete_multipart_upload {
      days_after_initiation = 7
    }
  }

  depends_on = [aws_s3_bucket_versioning.acquisition]
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
