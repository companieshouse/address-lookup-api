locals {
  stack_name            = "common-services"
  database_service_name = "address-rds"
  database_port         = data.aws_rds_cluster.aurora.port
  account_id            = data.aws_caller_identity.current.account_id

  tags = merge(module.iac_tags.tags, module.owner_tags.tags)

  stack_secrets_path       = "applications/${var.aws_profile}/${var.environment}/${local.stack_name}-stack"
  database_secrets_path    = "${local.stack_secrets_path}/${local.database_service_name}"
  acquisition_secrets_path = "${local.stack_secrets_path}/address-lookup-acquisition"
  stack_secrets            = data.vault_generic_secret.stack_secrets.data
  database_secrets         = data.vault_generic_secret.database_secrets.data
  acquisition_secrets      = data.vault_generic_secret.acquisition_secrets.data

  vpc_name            = local.stack_secrets["vpc_name"]
  data_subnet_pattern = local.database_secrets["data_subnet_pattern"]

  alarm_topic_name = coalesce(var.alarm_topic_name, "${local.stack_name}-${var.environment}-cloudwatch-alarms-notify-topic")

  # ---------------- Bucket layout (terraform/groups/s3) ----------------
  bucket_name    = "address-lookup-${var.environment}-${local.account_id}-landing"
  bucket_arn     = data.aws_s3_bucket.acquisition.arn
  kms_key_id     = coalesce(var.bucket_kms_key_id, "alias/${var.aws_profile}/environment-services-kms")
  prefix_root    = var.acquisition_root_prefix == "" ? "" : "${var.acquisition_root_prefix}/"
  control_prefix = "${local.prefix_root}control/"
  landing_prefix = "${local.prefix_root}landing/"

  # ---------------- Functions ----------------
  # Names are <function_name>-<environment>, as terraform-modules aws/lambda
  # forces for Upload.
  function_names = {
    download   = "address-lookup-ngd-download"
    extraction = "address-lookup-ngd-extraction"
    upload     = "address-lookup-ngd-upload"
  }
  lambda_names = { for key, name in local.function_names : key => "${name}-${var.environment}" }

  # Cursors follow the Lambda code's KeyLayout (feature/als-137-folder-layout):
  # control/cursors/<package>.json is Download's, <package>.extraction.json is
  # Extraction's and <package>.upload.json is Upload's. IAM lets each function
  # write only its own (iam.tf).
  cursor_prefix = "${local.control_prefix}cursors/"
  cursor_patterns = {
    download   = "*.json"
    extraction = "*.extraction.json"
    upload     = "*.upload.json"
  }
  # *.json also matches the other functions' cursors, so Download is denied them.
  cursor_denied_patterns = {
    download   = ["*.extraction.json", "*.upload.json"]
    extraction = []
    upload     = []
  }

  feed_prefixes = {
    download   = [for feed in var.download_feeds : "${local.landing_prefix}${feed}/"]
    extraction = [for feed in var.extraction_feeds : "${local.landing_prefix}${feed}/"]
    upload     = [for feed in var.upload_feeds : "${local.landing_prefix}${feed}/"]
  }

  common_env_vars = {
    for key in keys(local.function_names) : key => {
      CONTROL_BUCKET = local.bucket_name
      LANDING_BUCKET = local.bucket_name
      CONTROL_PREFIX = local.control_prefix
      LANDING_PREFIX = local.landing_prefix
      CURSOR_PREFIX  = local.cursor_prefix
    }
  }

  # The two functions outside the VPC, built with plain resources (unattached.tf).
  unattached = {
    download = {
      handler           = var.download_handler
      artifact_key      = var.download_artifact_key
      memory_size       = var.download_memory_size
      timeout           = var.download_timeout_seconds
      ephemeral_storage = 512
      env_vars = merge(local.common_env_vars.download, {
        OS_API_KEY_PARAMETER = local.os_api_key_parameter_name
      })
    }
    extraction = {
      handler           = var.extraction_handler
      artifact_key      = var.extraction_artifact_key
      memory_size       = var.extraction_memory_size
      timeout           = var.extraction_timeout_seconds
      ephemeral_storage = var.extraction_ephemeral_storage_size
      env_vars          = local.common_env_vars.extraction
    }
  }

  # ---------------- Parameters ----------------
  # aws/parameter-store names each parameter /<name_prefix>/<key>.
  parameter_arn_prefix      = "arn:aws:ssm:${var.aws_region}:${local.account_id}:parameter"
  acquisition_param_prefix  = "address-lookup/${var.environment}/acquisition"
  os_api_key_parameter_name = "/${local.acquisition_param_prefix}/os-api-key"
  os_api_key_parameter_arn  = "${local.parameter_arn_prefix}${local.os_api_key_parameter_name}"

  # Written by terraform/groups/aurora from Vault, as {"username","password","jdbcUrl"}.
  upload_credentials_parameter_name = "/address-lookup/${var.environment}/db/upload"
  upload_credentials_parameter_arn  = "${local.parameter_arn_prefix}${local.upload_credentials_parameter_name}"

  # terraform/groups/aurora names the endpoint address-lookup-<env>-ssm and its
  # security group address-lookup-<env>-ssm-endpoint. Private DNS is off.
  ssm_endpoint_name = "address-lookup-${var.environment}-ssm"
  ssm_endpoint_url  = "https://${data.aws_vpc_endpoint.ssm.dns_entry[0].dns_name}"

  dns_servers = [for server in data.aws_vpc_dhcp_options.vpc.domain_name_servers : server if server != "AmazonProvidedDNS"]
  dns_egress = {
    for pair in setproduct(local.dns_servers, ["udp", "tcp"]) :
    "${pair[1]}-${pair[0]}" => { cidr_ipv4 = "${pair[0]}/32", ip_protocol = pair[1] }
  }
}
