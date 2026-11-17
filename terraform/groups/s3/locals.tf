locals {
  bucket_prefix = "address-lookup-${var.environment}"
  kms_alias     = "alias/${var.aws_profile}/environment-services-kms"

  buckets = {
    landing = {
      name = "${local.bucket_prefix}-landing"
    }
  }

  account_id = data.aws_caller_identity.current.account_id
}
