# Both acquisition buckets are encrypted with a customer managed key, following
# the GuardDuty Malware Protection pattern in file-transfer-stack. GuardDuty can
# scan objects under a customer managed key provided the protection plan's role
# may use it, which the role policy in data.tf grants. An AWS managed key would
# not work, because its key policy cannot be extended to that role.
#
# The module's key policy delegates to the account root, so access is governed
# by the IAM policies of the GuardDuty role and each function's execution role.
module "acquisition_kms" {
  source = "git@github.com:companieshouse/terraform-modules//aws/kms?ref=1.0.434"

  description   = "Encrypts Ordnance Survey acquisition content in the ${var.environment} address-lookup source and scanned buckets"
  kms_key_alias = "${var.environment}/${local.name_prefix}-acquisition"

  tags = merge(module.iac_tags.tags, module.owner_tags.tags)
}
