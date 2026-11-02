
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
  description = "The name of the S3 bucket containing the Lambda release artefact"
}

variable "release_artifact_key" {
  type        = string
  description = "Key of the Lambda code object (address-lookup-db-migrate-<version>.zip) in the release bucket"
}

variable "snapshot_bucket_name" {
  type        = string
  description = "The versioned shared-services configs bucket the Concourse gather-snapshot job writes Flyway snapshots to (SNAPSHOT_BUCKET)"
}

# ---------------- OPTIONAL VARIABLES ----------------
variable "aws_region" {
  type        = string
  description = "The AWS region in which resources will be created"
  default     = "eu-west-2"
}

variable "snapshot_prefix" {
  type        = string
  description = "The key prefix of the db schema snapshots in the snapshot bucket; the function can read nothing else"
  default     = "address-lookup/db-schema-snapshots"
}

variable "lambda_runtime" {
  type        = string
  description = "The Lambda managed runtime"
  default     = "java21"
}

variable "lambda_handler_name" {
  type        = string
  description = "The Lambda function entrypoint"
  default     = "uk.gov.companieshouse.addresslookup.dbmigrate.Handler::handleRequest"
}

variable "lambda_memory_size" {
  type        = number
  description = "The amount of memory made available to the Lambda function at runtime in megabytes"
  default     = 1024
}

variable "lambda_timeout_seconds" {
  type        = number
  description = "The amount of time the Lambda function is allowed to run before being stopped. Concourse invokes it with --cli-read-timeout 900"
  default     = 900
}

variable "lambda_logs_retention_days" {
  type        = number
  description = "The number of days to retain the function's logs in CloudWatch"
  default     = 90
}

variable "lock_timeout" {
  type        = string
  description = "PostgreSQL lock_timeout for each migration (LOCK_TIMEOUT)"
  default     = "10s"
}

variable "concourse_iam_user_name" {
  type        = string
  description = "The IAM user whose access keys the environment's Concourse jobs use; the only principal allowed to invoke the function"
  default     = "concourse-platform"
}

variable "alarm_topic_name" {
  type        = string
  description = "The SNS topic the Errors alarm notifies. Defaults to the stack's common-services-<environment>-cloudwatch-alarms-notify-topic"
  default     = null
}
