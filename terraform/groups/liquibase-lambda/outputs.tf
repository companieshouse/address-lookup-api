output "lambda_function_name" {
  description = "The function Concourse invokes to apply a released db-schema changelog to Aurora"
  value       = module.lambda.lambda_function_name
}
