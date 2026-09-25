variable "aws_profile" {
  type        = string
  description = "The AWS profile to use for authentication, defined in environments vars."
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
  description = "The name of the S3 bucket containing the release artefacts for the Lambda functions and the db-schema changelogs"
}

variable "lambda_version" {
  type        = string
  description = "The version of the address-lookup Lambda artefacts to deploy, from the lambda-X.Y.Z release stream"
}

variable "lambda_artifact_key_prefix" {
  type        = string
  description = "The prefix under which Lambda and db-schema release artefacts are published in the release bucket"
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
