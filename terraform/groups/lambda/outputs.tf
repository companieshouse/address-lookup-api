output "schema_migrator_function_name" {
  description = "The function Concourse invokes to apply a released db-schema changelog to Aurora"
  value       = module.schema_migrator.lambda_function_name
}
