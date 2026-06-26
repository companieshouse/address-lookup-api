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
