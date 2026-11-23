locals {
  bucket_prefix = "address-lookup-${var.environment}"
  kms_key_id    = var.kms_key_id != null ? var.kms_key_id : "alias/${var.aws_profile}/environment-services-kms"

  account_id     = data.aws_caller_identity.current.account_id
  prefix_root    = var.acquisition_root_prefix == "" ? "" : "${var.acquisition_root_prefix}/"
  control_prefix = "${local.prefix_root}control/"
  landing_prefix = "${local.prefix_root}landing/"
}
