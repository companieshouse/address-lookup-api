
# ---------------- MANDATORY VARIABLES ----------------
variable "aws_profile" {
  type        = string
  description = "The AWS profile name; used as a prefix for Vault secrets"
}

variable "environment" {
  type        = string
  description = "The environment name to be used when creating AWS resources"
}

variable "acquisition_root_prefix" {
  type        = string
  description = "Reserved namespace before control/ and landing/; the current Lambda key layout requires an empty value"
  default     = ""

  validation {
    condition     = var.acquisition_root_prefix == ""
    error_message = "The current Lambda key layout requires an empty acquisition_root_prefix."
  }
}

variable "manage_guardduty_plan" {
  type        = bool
  description = "Create a GuardDuty plan in this account; disable when the bucket owner manages the plan"
  default     = true
}

variable "reconciliation_lambda_arns" {
  type        = map(string)
  description = "ARNs of deployed extraction and import Lambdas to invoke periodically"
  default     = {}

  validation {
    condition     = alltrue([for name, arn in var.reconciliation_lambda_arns : contains(["extraction", "import"], name) && length(trimspace(arn)) > 0])
    error_message = "Use extraction and import keys with non-empty Lambda ARNs."
  }
}

variable "reconciliation_schedule" {
  type        = string
  description = "EventBridge schedule for extraction and import reconciliation"
  default     = "rate(5 minutes)"
}

# ---------------- OPTIONAL VARIABLES ----------------
variable "aws_region" {
  type        = string
  description = "The AWS region in which resources will be created"
  default     = "eu-west-2"
}

variable "kms_key_id" {
  type        = string
  description = "Customer-managed encryption key ARN, ID or alias; defaults to alias/<aws_profile>/environment-services-kms"
  default     = null

  validation {
    condition     = var.kms_key_id == null ? true : length(trimspace(var.kms_key_id)) > 0
    error_message = "kms_key_id must be null (use the environment key) or a non-empty key identifier."
  }
}

variable "landing_retention_days" {
  type        = number
  description = "Days before Landing objects tagged retention=download expire"
  default     = 30
}

variable "noncurrent_retention_days" {
  type        = number
  description = "Days before noncurrent Landing object versions tagged retention=download expire"
  default     = 30
}

variable "audit_retention_days" {
  type        = number
  description = "Days before noncurrent control object versions expire"
  default     = 365
}
