# Vault stays the source of truth. Terraform copies the master and schema-owner credentials into
# SecureStrings under the environment's customer-managed KMS key, so no credential ever travels in a
# Lambda invocation payload or a pipeline log:
#   /address-lookup/<environment>/db/master        read only by the bootstrap Lambda
#   /address-lookup/<environment>/db/schema-owner  read by the bootstrap and db-migrate Lambdas
# Rotation: change the value in Vault, then apply this group. The parameters are overwritten and
# the bootstrap re-runs (its triggers include the schema owner's password hash), which sets the new
# password on the role. The Lambdas read the parameters on every invocation, so nothing is redeployed.
module "credentials" {
  source = "git@github.com:companieshouse/terraform-modules//aws/parameter-store?ref=1.0.442"

  name_prefix = local.parameter_prefix
  kms_key_id  = data.aws_kms_key.parameters.id
  secrets     = local.credentials
}
