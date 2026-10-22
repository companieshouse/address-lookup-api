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

variable "vpc_name" {
  type        = string
  description = "Value of the Name tag of the VPC the function runs in"
}

variable "private_subnet_pattern" {
  type        = string
  description = "Name tag pattern matching the private subnets the function runs in"
}

variable "aurora_security_group_id" {
  type        = string
  description = "ID of the Aurora cluster security group that must allow 5432 from db-migrate"
}

variable "lambda_runtime" {
  type        = string
  description = "Managed Lambda runtime. Change to java25 when the estate moves"
  default     = "java21"
}

variable "lambda_artefact_bucket" {
  type        = string
  description = "S3 bucket holding the shaded db-migrate jar"
}

variable "lambda_artefact_key" {
  type        = string
  description = "S3 key of the shaded db-migrate jar built from lambdas/db-migrate"
}

variable "snapshot_bucket_name" {
  type        = string
  description = "Name of the snapshot bucket (OPS-1)"
}

variable "snapshot_kms_key_arn" {
  type        = string
  description = "ARN of the KMS key encrypting the snapshot bucket (OPS-1)"
}

variable "db_credentials_parameter_name" {
  type        = string
  description = "SSM parameter name holding the schema owner credentials (OPS-3), e.g. /address-lookup/<env>/db/schema-owner"
}

variable "db_credentials_kms_key_arn" {
  type        = string
  description = "ARN of the KMS key encrypting the SSM parameter (OPS-3)"
}

variable "concourse_role_arn" {
  type        = string
  description = "ARN of this environment's Concourse role, the only principal allowed to invoke the function"
}

variable "alarm_sns_topic_arn" {
  type        = string
  description = "ARN of the SNS topic delivering alarms to the team channel"
}

variable "log_retention_in_days" {
  type        = number
  description = "Retention of the function log group in days"
  default     = 14
}
