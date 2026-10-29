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
