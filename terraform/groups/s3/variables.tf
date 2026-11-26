
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
  description = "Optional namespace before control/ and landing/; no leading or trailing slash"
  default     = ""

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
