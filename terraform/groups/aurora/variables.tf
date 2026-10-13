variable "aws_profile" {
  type        = string
  description = "The AWS profile to use for authentication, defined in environments vars."
}

variable "environment" {
  type        = string
  description = "The environment name, defined in environments vars."
}

variable "aws_region" {
  type        = string
  description = "The region in which to provision resources"
  default     = "eu-west-2"
}

variable "instances" {
  type        = map(object({}))
  description = "Map of instance configurations for the Aurora cluster"
  default = {
    primary = {}
  }
}

variable "release_bucket_name" {
  type        = string
  description = "The S3 bucket holding the db-bootstrap Lambda release artefact"
}

variable "db_bootstrap_release_artifact_key" {
  type        = string
  description = "Key of the db-bootstrap Lambda code (address-lookup-db-bootstrap-<version>.zip) in the release bucket"
}

variable "db_bootstrap_memory_size" {
  type        = number
  description = "Memory, in megabytes, for the db-bootstrap Lambda"
  default     = 512
}

variable "db_bootstrap_timeout_seconds" {
  type        = number
  description = "Timeout, in seconds, for the db-bootstrap Lambda"
  default     = 120
}

variable "rds_ca_bundle_path" {
  type        = string
  description = "Path, inside the Lambdas, of the RDS CA bundle (https://truststore.pki.rds.amazonaws.com/<region>/<region>-bundle.pem) used for sslmode=verify-full"
  default     = "/var/task/certs/eu-west-2-bundle.pem"
}

variable "db_migrate_function_name" {
  type        = string
  description = "function_name of the db-migrate Lambda (terraform/groups/db-migrate); its role may read the schema-owner parameter through the SSM endpoint"
  default     = "address-lookup-db-migrate"
}
