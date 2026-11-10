
# ---------------- MANDATORY VARIABLES ----------------
variable "aws_profile" {
  type        = string
  description = "The AWS profile name; used as a prefix for Vault secrets"
}

variable "environment" {
  type        = string
  description = "The environment name to be used when creating AWS resources"
}

variable "break_glass_role_arn" {
  type        = string
  description = "Approved break-glass IAM role ARN; its owner attaches the output read policy and manages audited access"

  validation {
    condition     = can(regex("^arn:aws:iam::[0-9]{12}:role/.+$", var.break_glass_role_arn))
    error_message = "Supply the approved IAM role ARN, not an assumed-role session ARN."
  }
}

# ---------------- OPTIONAL VARIABLES ----------------
variable "aws_region" {
  type        = string
  description = "The AWS region in which resources will be created"
  default     = "eu-west-2"
}

variable "landing_retention_days" {
  type        = number
  description = "Days before current and noncurrent versions under landing/raw/ and landing/extracted/ expire"
  default     = 30

  validation {
    condition     = var.landing_retention_days >= 1 && floor(var.landing_retention_days) == var.landing_retention_days
    error_message = "Landing retention must be a positive whole number of days."
  }
}

variable "audit_retention_days" {
  type        = number
  description = "Days before current and noncurrent manifest versions under control/acquisitions/ expire"
  default     = 365

  validation {
    condition     = var.audit_retention_days >= 1 && floor(var.audit_retention_days) == var.audit_retention_days
    error_message = "Audit retention must be a positive whole number of days."
  }
}
