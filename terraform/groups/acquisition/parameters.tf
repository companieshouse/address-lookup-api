# The OS Data Hub API key, copied from Vault to a SecureString that only the
# Download role can read. Vault: <stack path>/address-lookup-acquisition, key os_api_key.
module "acquisition_parameters" {
  source = "git@github.com:companieshouse/terraform-modules//aws/parameter-store?ref=1.0.442"

  name_prefix = local.acquisition_param_prefix
  kms_key_id  = data.aws_kms_key.environment.id
  secrets = {
    os-api-key = local.acquisition_secrets["os_api_key"]
  }
}
