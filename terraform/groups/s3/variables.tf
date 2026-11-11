
# ---------------- MANDATORY VARIABLES ----------------
variable "aws_profile" {
  type        = string
  description = "The AWS profile name; used as a prefix for Vault secrets"
}

variable "environment" {
  type        = string
  description = "The environment name to be used when creating AWS resources"
}

# ---------------- OPTIONAL VARIABLES ----------------
variable "aws_region" {
  type        = string
  description = "The AWS region in which resources will be created"
  default     = "eu-west-2"
}

variable "landing_retention_days" {
  type        = number
  description = "Days before objects under raw/ and extracted/ (and in Quarantine) expire. To be agreed in ALS-102"
  default     = 30
}

variable "noncurrent_retention_days" {
  type        = number
  description = "Days before noncurrent object versions under expiring prefixes are permanently deleted"
  default     = 30
}

variable "audit_retention_days" {
  type        = number
  description = "Days before objects under sources/acquisitions/ expire; kept longer than landing data for audit. To be agreed in ALS-102"
  default     = 365
}

variable "quarantine_lambda_role_name" {
  type        = string
  description = "Name of the Quarantine Lambda's IAM role; the only principal allowed to write to the Quarantine bucket"
  default     = "address-lookup-quarantine-lambda"
}

variable "break_glass_role_name" {
  type        = string
  description = "Name of the break-glass IAM role; the only principal allowed to read the Quarantine bucket"
  default     = "address-lookup-quarantine-break-glass"
}
