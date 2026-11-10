output "bucket_name" {
  description = "Single versioned acquisition bucket"
  value       = aws_s3_bucket.this.id
}

output "bucket_arn" {
  value = aws_s3_bucket.this.arn
}

output "lambda_role_arns" {
  description = "Storage-scoped execution roles for future Lambda deployment groups; add logging, networking and EventBridge permissions there"
  value       = { for name, role in aws_iam_role.lambda : name => role.arn }
}

output "lambda_environment" {
  description = "All existing acquisition bucket settings point to the same bucket"
  value = {
    SOURCE_BUCKET  = aws_s3_bucket.this.id
    INGEST_BUCKET  = aws_s3_bucket.this.id
    SCANNED_BUCKET = aws_s3_bucket.this.id
  }
}

output "scan_prefixes" {
  description = "Only these payload prefixes may be included in the future GuardDuty protection plan and scan event rules"
  value       = ["landing/raw/", "landing/extracted/"]
}

output "break_glass_read_policy_arn" {
  description = "The break-glass role owner must attach this managed policy to the approved role"
  value       = aws_iam_policy.break_glass_storage.arn
}
