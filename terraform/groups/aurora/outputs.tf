output "cluster_endpoint" {
  description = "Writer endpoint for the Aurora PostgreSQL cluster"
  value       = module.aurora_postgres.cluster_endpoint
}

output "reader_endpoint" {
  description = "Reader endpoint for the Aurora PostgreSQL cluster"
  value       = module.aurora_postgres.reader_endpoint
}

output "cluster_id" {
  description = "Identifier of the Aurora PostgreSQL cluster"
  value       = module.aurora_postgres.cluster_id
}

output "database_name" {
  description = "Name of the application database"
  value       = local.database_name
}

output "database_port" {
  description = "Port the Aurora PostgreSQL cluster listens on"
  value       = 5432
}

output "security_group_id" {
  description = "The Aurora cluster's security group"
  value       = module.aurora_postgres.security_group_id
}

output "master_credentials_parameter_name" {
  description = "SecureString with the master credentials; read only by the db-bootstrap Lambda"
  value       = local.master_parameter_name
}

output "schema_owner_credentials_parameter_name" {
  description = "SecureString with the schema owner's username, password and jdbcUrl; read by the db-bootstrap and db-migrate Lambdas"
  value       = local.schema_owner_parameter_name
}

output "ssm_endpoint_id" {
  description = "The SSM interface endpoint used by the migration Lambdas (private DNS off)"
  value       = aws_vpc_endpoint.ssm.id
}

output "ssm_endpoint_dns_name" {
  description = "DNS name of the SSM interface endpoint (AWS_ENDPOINT_URL_SSM)"
  value       = aws_vpc_endpoint.ssm.dns_entry[0].dns_name
}

output "ssm_endpoint_security_group_id" {
  description = "The SSM endpoint's security group; terraform/groups/db-migrate adds its ingress rule to it"
  value       = aws_security_group.ssm_endpoint.id
}

output "db_bootstrap_function_name" {
  description = "The db-bootstrap Lambda; invoke with {\"command\":\"verify\"} to re-check the database"
  value       = module.db_bootstrap.lambda_function_name
}

output "db_bootstrap_result" {
  description = "The last bootstrap's verification report (PostGIS version, owner, database state, TLS checks)"
  value       = jsondecode(aws_lambda_invocation.db_bootstrap.result)
}
