output "lambda_function_names" {
  description = "The Download, Extraction and Upload functions; schedules and the Supply pipeline invoke these"
  value = {
    download   = aws_lambda_function.unattached["download"].function_name
    extraction = aws_lambda_function.unattached["extraction"].function_name
    upload     = module.upload.lambda_function_name
  }
}

output "lambda_function_arns" {
  description = "The functions' ARNs, for the schedule tickets' targets"
  value = {
    download   = aws_lambda_function.unattached["download"].arn
    extraction = aws_lambda_function.unattached["extraction"].arn
    upload     = module.upload.lambda_function_arn
  }
}

output "lambda_role_names" {
  description = "The functions' execution roles, for later tickets that add workload permissions"
  value = {
    download   = aws_iam_role.unattached["download"].name
    extraction = aws_iam_role.unattached["extraction"].name
    upload     = module.upload.lambda_role_name
  }
}

output "upload_security_group_id" {
  description = "The Upload function's security group; Aurora admits it on the database port"
  value       = module.upload.security_group_id
}

output "cursor_prefix" {
  description = "The cursors' prefix in Control; each function may write only its own cursor objects under it"
  value       = local.cursor_prefix
}

output "os_api_key_parameter_name" {
  description = "The SecureString holding the OS Data Hub API key"
  value       = local.os_api_key_parameter_name
}
