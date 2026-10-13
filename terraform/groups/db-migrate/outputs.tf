output "lambda_function_name" {
  description = "The function the environment's Concourse update, repair and API check-db-schema jobs invoke"
  value       = module.lambda.lambda_function_name
}

output "lambda_role_arn" {
  description = "The function's execution role"
  value       = module.lambda.lambda_role_arn
}

output "security_group_id" {
  description = "The function's security group; Aurora admits it on the database port"
  value       = module.lambda.security_group_id
}

output "credentials_parameter_name" {
  description = "The SecureString holding the schema owner's username, password and jdbcUrl"
  value       = local.credentials_parameter_name
}

output "ssm_endpoint_dns_name" {
  description = "The SSM interface endpoint the function uses (terraform/groups/aurora; private DNS is off)"
  value       = data.aws_vpc_endpoint.ssm.dns_entry[0].dns_name
}
