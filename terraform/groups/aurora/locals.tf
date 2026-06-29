locals {
  service_name = "addressdb"
  stack_name   = "common-services"

  data_subnet_pattern = data.vault_generic_secret.aurora.data["data_subnet_pattern"]
  master_username     = data.vault_generic_secret.aurora.data["master_username"]
  master_password     = data.vault_generic_secret.aurora.data["master_password"]

  vpc_name = data.vault_generic_secret.stack_secrets.data["vpc_name"]
}
