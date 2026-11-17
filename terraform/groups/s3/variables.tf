
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
  description = "Days before objects under landing/ expire"
  default     = 30
}

variable "noncurrent_retention_days" {
  type        = number
  description = "Days before noncurrent object versions under expiring prefixes are permanently deleted"
  default     = 30
}

variable "audit_retention_days" {
  type        = number
  description = "Days before noncurrent control/ versions expire; current plans, cursors and receipts are retained for recovery"
  default     = 365
}
