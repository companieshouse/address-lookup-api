output "source_bucket_name" {
  description = "Red zone bucket holding acquisition plans, receipts and unverified Ordnance Survey downloads"
  value       = aws_s3_bucket.source.id
}

output "scanned_bucket_name" {
  description = "Clean zone bucket holding certified manifests, ready markers and extracted CSV content"
  value       = aws_s3_bucket.scanned.id
}

output "event_bus_name" {
  description = "Name of the EventBridge bus carrying acquisition commands"
  value       = aws_cloudwatch_event_bus.acquisition.name
}

output "dead_letter_queue_url" {
  description = "URL of the queue holding asynchronous invocations that exhausted their retries"
  value       = aws_sqs_queue.dead_letter.id
}

output "lambda_function_names" {
  description = "Deployed Lambda function names, keyed by pipeline stage"
  value       = { for name, lambda in module.lambda : name => lambda.lambda_function_name }
}

output "importer_security_group_id" {
  description = "Security group authorised to reach the Aurora cluster"
  value       = module.lambda["import"].security_group_id
}
