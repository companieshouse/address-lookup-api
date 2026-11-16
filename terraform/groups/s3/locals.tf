locals {
  bucket_prefix = "address-lookup-${var.environment}"

  buckets = {
    landing = {
      name = "${local.bucket_prefix}-landing"
    }
  }

  account_id = data.aws_caller_identity.current.account_id
}
