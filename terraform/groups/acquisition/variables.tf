
# ---------------- MANDATORY VARIABLES ----------------
variable "aws_profile" {
  type        = string
  description = "The AWS profile name; used as a prefix for Vault secrets"
}

variable "environment" {
  type        = string
  description = "The environment name to be used when creating AWS resources"
}

variable "release_bucket_name" {
  type        = string
  description = "The name of the S3 bucket containing the Lambda release artefacts"
}

variable "download_artifact_key" {
  type        = string
  description = "Key of the Download Lambda code (address-lookup-api/address-lookup-ngd-download-<version>.zip) in the release bucket"
}

variable "extraction_artifact_key" {
  type        = string
  description = "Key of the Extraction Lambda code (address-lookup-api/address-lookup-ngd-extraction-<version>.zip) in the release bucket"
}

variable "upload_artifact_key" {
  type        = string
  description = "Key of the Upload Lambda code (address-lookup-api/address-lookup-ngd-upload-<version>.zip) in the release bucket"
}

variable "acquisition_root_prefix" {
  type        = string
  description = "Namespace before control/ and landing/ in the acquisition bucket; must match terraform/groups/s3. No leading or trailing slash"

  validation {
    condition     = var.acquisition_root_prefix == "" || can(regex("^[a-zA-Z0-9._-]+(/[a-zA-Z0-9._-]+)*$", var.acquisition_root_prefix))
    error_message = "Use an empty prefix or slash-separated literal path segments without leading or trailing slashes."
  }
}

# ---------------- OPTIONAL VARIABLES ----------------
variable "aws_region" {
  type        = string
  description = "The AWS region in which resources will be created"
  default     = "eu-west-2"
}

variable "bucket_kms_key_id" {
  type        = string
  description = "The acquisition bucket's SSE-KMS key (ARN, ID or alias). Defaults to alias/<aws_profile>/environment-services-kms, as terraform/groups/s3 does"
  default     = null
}

variable "lambda_runtime" {
  type        = string
  description = "The Lambda managed runtime of all three functions"
  default     = "java25"
}

variable "lambda_logs_retention_days" {
  type        = number
  description = "The number of days to retain each function's logs in CloudWatch"
  default     = 90
}

# Feeds are the first key segment under landing/ (initial_full, daily_cou, monthly_full).
# monthly_full is out of scope here: its archive functions add their own permissions.
variable "download_feeds" {
  type        = list(string)
  description = "Landing feed prefixes the Download Lambda may list, read and write"
  default     = ["daily_cou", "initial_full"]
}

variable "extraction_feeds" {
  type        = list(string)
  description = "Landing feed prefixes the Extraction Lambda may list, read, tag-check and write"
  default     = ["daily_cou", "initial_full"]
}

variable "upload_feeds" {
  type        = list(string)
  description = "Landing feed prefixes the Upload Lambda may list, read, tag-check and write receipts to. A FULL is loaded by the Supply pipeline, not by this function"
  default     = ["daily_cou"]
}

variable "download_handler" {
  type        = string
  description = "The Download Lambda entrypoint"
  default     = "uk.gov.companieshouse.addresslookup.lambda.download.Handler::handleRequest"
}

variable "download_memory_size" {
  type        = number
  description = "Download Lambda memory in MB (design: max, for multipart streaming)"
  default     = 10240
}

variable "download_timeout_seconds" {
  type        = number
  description = "Download Lambda timeout. It stops itself before the cut-off and resumes on the next run"
  default     = 900
}

variable "extraction_handler" {
  type        = string
  description = "The Extraction Lambda entrypoint"
  default     = "uk.gov.companieshouse.addresslookup.lambda.unzip.Handler::handleRequest"
}

variable "extraction_memory_size" {
  type        = number
  description = "Extraction Lambda memory in MB (design: max)"
  default     = 10240
}

variable "extraction_timeout_seconds" {
  type        = number
  description = "Extraction Lambda timeout. It stops itself before the cut-off and resumes on the next run"
  default     = 900
}

variable "extraction_ephemeral_storage_size" {
  type        = number
  description = "Extraction Lambda /tmp size in MB, for unpacking the zips"
  default     = 10240
}

variable "upload_handler" {
  type        = string
  description = "The Upload Lambda entrypoint"
  default     = "uk.gov.companieshouse.addresslookup.lambda.upload.Handler::handleRequest"
}

variable "upload_memory_size" {
  type        = number
  description = "Upload Lambda memory in MB (design: 4 GB)"
  default     = 4096
}

variable "upload_timeout_seconds" {
  type        = number
  description = "Upload Lambda timeout. Must stay above the JDBC socketTimeout (840 s)"
  default     = 900
}

variable "upload_reserved_concurrency" {
  type        = number
  description = "Upload Lambda reserved concurrency. Each run holds one database connection"
  default     = 1
}

variable "alarm_topic_name" {
  type        = string
  description = "The SNS topic the Errors alarms notify. Defaults to the stack's common-services-<environment>-cloudwatch-alarms-notify-topic"
  default     = null
}
