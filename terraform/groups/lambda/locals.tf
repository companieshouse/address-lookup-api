locals {
  stack_name   = "common-services" # this must match the stack name the service deploys into
  service_name = "address-lookup-api"
  kms_alias    = "alias/${var.aws_profile}/environment-services-kms"

  name_prefix = "address-lookup"

  # The acquisition pipeline moves Ordnance Survey supply through two buckets.
  #
  #   source  - the red zone. Unverified bytes downloaded straight from the OS
  #             Data Hub land here, along with the immutable acquisition plans
  #             and the per-step receipts that make each Lambda idempotent.
  #   scanned - the clean zone. Only content that GuardDuty has tagged
  #             NO_THREATS_FOUND is copied here, and only the import Lambda
  #             reads from it.
  #
  # Both buckets must be versioned: ReleaseStore records the object version in
  # its receipts and every later read pins that exact version, so a bucket
  # without versioning fails closed with "Versioned bucket required".
  source_bucket_name  = "${var.environment}-${local.name_prefix}-source"
  scanned_bucket_name = "${var.environment}-${local.name_prefix}-scanned"

  event_bus_name = "${var.environment}-${local.name_prefix}-acquisition"

  vpc_name                   = local.stack_secrets["vpc_name"]
  application_subnet_pattern = local.stack_secrets["application_subnet_pattern"]
  application_subnet_ids     = data.aws_subnets.application.ids

  # The importer needs the Aurora writer endpoint published by the aurora group.
  # Both groups are deployed from this repository, so the names are known here
  # rather than being threaded through remote state.
  database_service_name = "address-rds"
  database_name         = "addressdb"
  database_sg_name      = lower("${var.environment}-${local.database_service_name}-rds-sg")
  database_endpoint     = data.aws_rds_cluster.aurora.endpoint
  database_port         = data.aws_rds_cluster.aurora.port

  # sslmode=verify-full matches the cluster's rds.force_ssl=1 parameter.
  jdbc_url = "jdbc:postgresql://${local.database_endpoint}:${local.database_port}/${local.database_name}?sslmode=verify-full&sslrootcert=/opt/rds-ca.pem"

  # Secrets
  stack_secrets        = data.vault_generic_secret.stack_secrets.data
  stack_secrets_path   = "applications/${var.aws_profile}/${var.environment}/${local.stack_name}-stack"
  service_secrets      = data.vault_generic_secret.service_secrets.data
  service_secrets_path = "${local.stack_secrets_path}/${local.service_name}"

  os_api_key       = local.service_secrets["os_api_key"]
  importer_db_user = local.service_secrets["importer_db_user"]
  importer_db_pass = local.service_secrets["importer_db_password"]

  os_packages = jsonencode(var.os_packages)

  # Every function is released from the same Maven reactor, so they share a
  # version and differ only by artefact name.
  artifact_key = { for name in keys(local.lambda_functions) :
    name => "${var.lambda_artifact_key_prefix}/${local.name_prefix}-lambda-${name}-${var.lambda_version}.zip"
  }

  # All five handlers are declared as uk.gov.companieshouse...lamda.Handler.
  # The scan module's package is currently misspelled as `uk.goc`; this local
  # tracks the code as it stands so that Terraform does not silently deploy a
  # function that cannot resolve its handler. Remove the special case once the
  # package is corrected in lambdas/scan.
  handler = {
    discovery = "uk.gov.companieshouse.addresslookup.lamda.Handler::handleRequest"
    download  = "uk.gov.companieshouse.addresslookup.lamda.Handler::handleRequest"
    scan      = "uk.goc.companieshouse.addresslookup.lamda.Handler::handleRequest"
    unzip     = "uk.gov.companieshouse.addresslookup.lamda.Handler::handleRequest"
    import    = "uk.gov.companieshouse.addresslookup.lamda.Handler::handleRequest"
  }

  lambda_functions = {
    # Scheduled. Asks the OS Data Hub what is available, writes one immutable
    # acquisition plan to source/acquisitions/<target>/<releaseId>.json, then
    # emits a DOWNLOAD command per dataset.
    discovery = {
      description = "Finds a new Ordnance Survey supply and records an immutable acquisition plan"
      memory_size = 1024
      timeout     = 300
      env = {
        SOURCE_BUCKET = aws_s3_bucket.source.id
        EVENT_BUS     = aws_cloudwatch_event_bus.acquisition.name
        OS_API_KEY    = local.os_api_key
        OS_PACKAGES   = local.os_packages
      }
      policies = [
        data.aws_iam_policy_document.discovery.json
      ]
      event_rules = [
        {
          name                = "${var.environment}-${local.name_prefix}-discovery"
          description         = "Periodically look for a new Ordnance Survey change-only update"
          schedule_expression = var.discovery_schedule_expression
          target_input        = jsonencode({ detail = { mode = "COU" } })
        }
      ]
    }

    # Streams one OS ZIP into source/quarantine/zips/ and writes a download
    # receipt. Needs the full 15 minutes: OsClient's request timeout is 840s.
    download = {
      description = "Streams an Ordnance Survey ZIP into the quarantine area of the source bucket"
      memory_size = 2048
      timeout     = 900
      env = {
        SOURCE_BUCKET = aws_s3_bucket.source.id
        OS_API_KEY    = local.os_api_key
        MAX_ZIP_BYTES = tostring(var.max_zip_bytes)
      }
      policies = [
        data.aws_iam_policy_document.download.json
      ]
      event_rules = [
        {
          name           = "${var.environment}-${local.name_prefix}-download"
          description    = "Download a dataset named by an acquisition plan"
          event_bus_name = aws_cloudwatch_event_bus.acquisition.name
          event_pattern = jsonencode({
            source        = ["os.acquisition"]
            "detail-type" = ["DOWNLOAD"]
          })
        }
      ]
    }

    # The reconciler. Reacts to GuardDuty verdicts, promotes clean content into
    # the scanned bucket, and re-issues whichever command is still outstanding.
    # The scheduled sweep is what makes a dropped event non-fatal.
    scan = {
      description = "Reconciles GuardDuty scan results and drives each release forward"
      memory_size = 1024
      timeout     = 900
      env = {
        SOURCE_BUCKET           = aws_s3_bucket.source.id
        SCANNED_BUCKET          = aws_s3_bucket.scanned.id
        EVENT_BUS               = aws_cloudwatch_event_bus.acquisition.name
        ACQUISITION_SWEEP_LIMIT = tostring(var.acquisition_sweep_limit)
      }
      policies = [
        data.aws_iam_policy_document.scan.json
      ]
      event_rules = [
        {
          # GuardDuty publishes to the default bus, not to our custom bus.
          name        = "${var.environment}-${local.name_prefix}-scan-results"
          description = "React to a GuardDuty malware scan verdict on an acquisition object"
          event_pattern = jsonencode({
            source        = ["aws.guardduty"]
            "detail-type" = ["GuardDuty Malware Protection Object Scan Result"]
            detail = {
              s3ObjectDetails = {
                bucketName = [aws_s3_bucket.source.id, aws_s3_bucket.scanned.id]
              }
            }
          })
        },
        {
          name                = "${var.environment}-${local.name_prefix}-scan-sweep"
          description         = "Safety net sweep in case a GuardDuty event was never delivered"
          schedule_expression = var.reconcile_schedule_expression
          # ScanResultsService accepts only os.release/RECONCILE_SCANS or a
          # GuardDuty verdict, and target_input replaces the whole event.
          target_input = jsonencode({
            source        = "os.release"
            "detail-type" = "RECONCILE_SCANS"
            detail        = {}
          })
        }
      ]
    }

    # Expands the clean ZIP and streams the primary CSV into the scanned
    # bucket, where it is scanned again before the importer may read it.
    unzip = {
      description = "Expands a clean Ordnance Survey ZIP and streams the primary CSV out of the red zone"
      memory_size = 2048
      timeout     = 900
      env = {
        SOURCE_BUCKET       = aws_s3_bucket.source.id
        SCANNED_BUCKET      = aws_s3_bucket.scanned.id
        MAX_EXTRACTED_BYTES = tostring(var.max_extracted_bytes)
      }
      policies = [
        data.aws_iam_policy_document.unzip.json
      ]
      event_rules = [
        {
          name           = "${var.environment}-${local.name_prefix}-unzip"
          description    = "Extract a dataset whose ZIP has been certified clean"
          event_bus_name = aws_cloudwatch_event_bus.acquisition.name
          event_pattern = jsonencode({
            source        = ["os.acquisition"]
            "detail-type" = ["UNZIP"]
          })
        }
      ]
    }

    # The only function with database access. Reserved concurrency of 1 makes
    # it the single writer against Aurora.
    import = {
      description = "COPYs certified CSV content into Aurora and promotes the release"
      memory_size = 3008
      timeout     = 900
      env = {
        SCANNED_BUCKET         = aws_s3_bucket.scanned.id
        JDBC_URL               = local.jdbc_url
        DB_USER                = local.importer_db_user
        DB_PASSWORD            = local.importer_db_pass
        IMPORTER_SWEEP_LIMIT   = tostring(var.importer_sweep_limit)
        MAX_PRIMARY_CSV_BYTES  = tostring(var.max_zip_bytes)
        SPRING_MAIN_BANNERMODE = "off"
      }
      policies = [
        data.aws_iam_policy_document.import.json
      ]
      event_rules = [
        {
          name                = "${var.environment}-${local.name_prefix}-import-sweep"
          description         = "Sweep for release manifests that are ready to import"
          schedule_expression = var.reconcile_schedule_expression
          target_input = jsonencode({
            source        = "os.release"
            "detail-type" = "RECONCILE_IMPORTS"
            detail        = {}
          })
        }
      ]
    }
  }
}
