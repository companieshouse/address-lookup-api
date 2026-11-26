output "bucket_names" {
  description = "Acquisition bucket name, keyed by landing for consumer compatibility"
  value       = { landing = aws_s3_bucket.acquisition.id }
}

output "bucket_arns" {
  description = "Acquisition bucket ARN, keyed by landing for consumer compatibility"
  value       = { landing = aws_s3_bucket.acquisition.arn }
}

output "source_bucket" {
  description = "SOURCE_BUCKET for acquisition Lambdas and the importer"
  value       = aws_s3_bucket.acquisition.id
}

output "acquisition_prefixes" {
  description = "Environment-specific control and landing key prefixes"
  value       = { control = local.control_prefix, landing = local.landing_prefix }
}

output "kms_key_arn" {
  description = "Existing customer-managed KMS key used for acquisition bucket encryption"
  value       = data.aws_kms_key.acquisition.arn
}

output "download_storage_policy_json" {
  description = "Prefix-scoped policy JSON for the Download Lambda module's additional_policies"
  value       = data.aws_iam_policy_document.download_storage.json
}
