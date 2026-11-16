output "bucket_names" {
  description = "The single acquisition bucket name, keyed by landing for state compatibility"
  value       = { for key, bucket in aws_s3_bucket.this : key => bucket.id }
}

output "bucket_arns" {
  description = "The single acquisition bucket ARN, keyed by landing for state compatibility"
  value       = { for key, bucket in aws_s3_bucket.this : key => bucket.arn }
}

output "source_bucket" {
  description = "SOURCE_BUCKET for acquisition Lambdas and the importer"
  value       = aws_s3_bucket.this["landing"].id
}

output "download_storage_policy_json" {
  description = "Prefix-scoped policy JSON for the Download Lambda module's additional_policies"
  value       = data.aws_iam_policy_document.download_storage.json
}
