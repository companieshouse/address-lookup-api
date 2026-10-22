output "function_name" {
  description = "Name of the db-migrate Lambda function"
  value       = aws_lambda_function.db_migrate.function_name
}

output "function_arn" {
  description = "ARN of the db-migrate Lambda function"
  value       = aws_lambda_function.db_migrate.arn
}

output "security_group_id" {
  description = "ID of the db-migrate security group"
  value       = aws_security_group.db_migrate.id
}
