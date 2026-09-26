output "liquibase_function_name" {
  description = "The function Concourse invokes to apply a released db-schema changelog to Aurora"
  value       = module.liquibase_lambda.lambda_function_name
}
