variable "aws_profile" {
  type        = string
  description = "The AWS profile to use for authentication, defined in environments vars."
}

variable "aws_account" {
  type        = string
  description = "The AWS account name, e.g. development, staging or live. Used to locate the account's shared S3 access logging bucket."
}

variable "environment" {
  type        = string
  description = "The environment name, defined in environments vars."
}

variable "aws_region" {
  type        = string
  description = "The region in which to provision resources"
  default     = "eu-west-2"
}

variable "release_bucket_name" {
  type        = string
  description = "The name of the S3 bucket containing the release artefacts for the Lambda functions"
}

variable "lambda_version" {
  type        = string
  description = "The version of the address-lookup Lambda artefacts to deploy. All six functions, including the schema migrator, are released together from the same reactor build and therefore share a version."
}

variable "lambda_artifact_key_prefix" {
  type        = string
  description = "The prefix under which Lambda release artefacts are published in the release bucket"
  default     = "address-lookup-api"
}

variable "lambda_runtime" {
  type        = string
  description = "The Lambda runtime to use for all functions"
  default     = "java21"
}

variable "lambda_logs_retention_days" {
  type        = number
  description = "The number of days to retain Lambda logs in CloudWatch"
  default     = 90
}

variable "os_packages" {
  type        = map(string)
  description = "Map of Ordnance Survey dataset name to OS Data Hub package identifier, supplied to the discovery Lambda as OS_PACKAGES."
  default     = {}
}

variable "discovery_schedule_expression" {
  type        = string
  description = "Schedule on which the discovery Lambda looks for a new Ordnance Survey supply"
  default     = "cron(0 2 ? * MON *)"
}

variable "reconcile_schedule_expression" {
  type        = string
  description = "Schedule on which the scan and import Lambdas sweep for work that no event delivered. This is the safety net that makes the pipeline self-healing."
  default     = "rate(15 minutes)"
}

variable "max_zip_bytes" {
  type        = number
  description = "Largest Ordnance Survey ZIP the download Lambda will stream into the source bucket"
  default     = 536870912
}

variable "max_extracted_bytes" {
  type        = number
  description = "Largest total expanded size the unzip Lambda will tolerate for a single archive"
  default     = 1073741824
}

variable "acquisition_sweep_limit" {
  type        = number
  description = "Maximum number of acquisition plans the scan Lambda reconciles per invocation"
  default     = 20
}

variable "importer_sweep_limit" {
  type        = number
  description = "Maximum number of release manifests the import Lambda reconciles per invocation"
  default     = 20
}

variable "source_bucket_expiration_days" {
  type        = number
  description = "Number of days after which quarantined downloads and extracts are expired from the source bucket"
  default     = 30
}

variable "scanned_bucket_expiration_days" {
  type        = number
  description = "Number of days after which scanned CSV objects are expired from the scanned bucket. Manifests and ready markers are small and are retained."
  default     = 90
}
