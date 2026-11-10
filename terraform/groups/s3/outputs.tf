output "bucket_names" {
  description = "The Control, Landing and Quarantine bucket names, keyed by purpose"
  value       = { for key, bucket in aws_s3_bucket.this : key => bucket.id }
}

output "bucket_arns" {
  description = "The Control, Landing and Quarantine bucket ARNs, keyed by purpose"
  value       = { for key, bucket in aws_s3_bucket.this : key => bucket.arn }
}
