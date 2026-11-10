provider "aws" {
  region                      = "eu-west-2"
  access_key                  = "testing"
  secret_key                  = "testing"
  skip_credentials_validation = true
  skip_requesting_account_id  = true
  skip_metadata_api_check     = true
}

override_data {
  target = data.aws_caller_identity.current
  values = {
    account_id = "123456789012"
  }
}

override_resource {
  target = aws_s3_bucket.this
  values = {
    arn = "arn:aws:s3:::address-lookup-test-acquisition-123456789012"
    id  = "address-lookup-test-acquisition-123456789012"
  }
}

# Override every managed resource: policy-document data sources still use the real
# provider, but no AWS create, update or delete operation is performed.
override_resource {
  target = aws_iam_role.lambda
  values = {
    arn = "arn:aws:iam::123456789012:role/address-lookup-test-quarantine"
  }
}

override_resource {
  target = aws_iam_role_policy.lambda_storage
}

override_resource {
  target = aws_iam_policy.break_glass_storage
}

override_resource {
  target = aws_s3_bucket_versioning.this
}

override_resource {
  target = aws_s3_bucket_public_access_block.this
}

override_resource {
  target = aws_s3_bucket_ownership_controls.this
}

override_resource {
  target = aws_s3_bucket_server_side_encryption_configuration.this
}

override_resource {
  target = aws_s3_bucket_policy.this
}

override_resource {
  target = aws_s3_bucket_lifecycle_configuration.this
}

variables {
  aws_profile          = "development-eu-west-2"
  environment          = "test"
  break_glass_role_arn = "arn:aws:iam::123456789012:role/approved-break-glass"
}

run "storage_boundaries" {
  command = apply

  assert {
    condition     = length(jsondecode(data.aws_iam_policy_document.bucket.json).Statement) == 4
    error_message = "The TLS deny and all three Quarantine protections must be present."
  }

  assert {
    condition = alltrue([
      for statement in jsondecode(data.aws_iam_policy_document.bucket.json).Statement :
      statement.Effect == "Deny" && statement.Action == "s3:*" &&
      statement.Principal == "*" &&
      statement.Condition.Bool["aws:SecureTransport"] == "false" &&
      toset(statement.Resource) == toset([aws_s3_bucket.this.arn, "${aws_s3_bucket.this.arn}/*"])
      if statement.Sid == "DenyInsecureTransport"
      ]) && length([
      for statement in jsondecode(data.aws_iam_policy_document.bucket.json).Statement :
      statement if statement.Sid == "DenyInsecureTransport"
    ]) == 1
    error_message = "HTTP must be explicitly denied for both bucket operations and object versions."
  }

  assert {
    condition = alltrue([
      for statement in jsondecode(data.aws_iam_policy_document.bucket.json).Statement :
      statement.Effect == "Deny" &&
      statement.Resource == "${aws_s3_bucket.this.arn}/quarantine/*" &&
      statement.Condition.ArnNotEquals["aws:PrincipalArn"] == var.break_glass_role_arn &&
      contains(statement.Action, "s3:GetObject*")
      if statement.Sid == "DenyReadExceptBreakGlass"
    ])
    error_message = "Quarantine reads, including pinned versions, must be denied except to break-glass."
  }

  assert {
    condition = alltrue([
      for statement in jsondecode(data.aws_iam_policy_document.bucket.json).Statement :
      statement.Effect == "Deny" &&
      statement.Resource == "${aws_s3_bucket.this.arn}/quarantine/*" &&
      statement.Condition.ArnNotEquals["aws:PrincipalArn"] == aws_iam_role.lambda["quarantine"].arn &&
      contains(statement.Action, "s3:PutObject*") && contains(statement.Action, "s3:DeleteObject*")
      if statement.Sid == "DenyWriteExceptQuarantineLambda"
    ])
    error_message = "Only the Quarantine role may modify Quarantine, including version tags and deletes."
  }

  assert {
    condition = alltrue([
      for statement in jsondecode(data.aws_iam_policy_document.bucket.json).Statement :
      statement.Effect == "Deny" && statement.Principal == "*" &&
      statement.Condition.ArnNotEquals["aws:PrincipalArn"] == var.break_glass_role_arn &&
      toset(statement.Condition.StringNotLike["s3:prefix"]) == toset(["control/*", "landing/*"]) &&
      contains(statement.Action, "s3:ListBucketVersions")
      if statement.Sid == "DenyListingOutsideNonQuarantinePrefixes"
    ])
    error_message = "Unfiltered and Quarantine listings must be denied to ordinary roles."
  }

  assert {
    condition = alltrue(flatten([
      for name, policy in data.aws_iam_policy_document.lambda_storage : [
        for statement in jsondecode(policy.json).Statement :
        alltrue([for resource in flatten([statement.Resource]) :
          startswith(resource, "${aws_s3_bucket.this.arn}/control/") ||
          startswith(resource, "${aws_s3_bucket.this.arn}/landing/") ||
          (name == "quarantine" && resource == "${aws_s3_bucket.this.arn}/quarantine/*")
        ]) if statement.Sid != "ListPermittedPrefixes"
      ]
    ]))
    error_message = "Lambda object permissions must not grant bucket-wide or other-bucket access."
  }

  assert {
    condition = alltrue([
      for statement in jsondecode(data.aws_iam_policy_document.lambda_storage["discovery"].json).Statement :
      statement.Resource == "${aws_s3_bucket.this.arn}/control/address-release/active.json"
      if statement.Sid == "ReadObjects"
      ]) && alltrue([
      for statement in jsondecode(data.aws_iam_policy_document.lambda_storage["download"].json).Statement :
      !contains(flatten([statement.Resource]), "${aws_s3_bucket.this.arn}/landing/extracted/*")
    ])
    error_message = "Discovery must read only its cursor; Download must not access extracted payloads."
  }

  assert {
    condition = alltrue([
      for statement in jsondecode(data.aws_iam_policy_document.lambda_storage["upload"].json).Statement :
      statement.Resource == "${aws_s3_bucket.this.arn}/control/reconciliation/imports.json"
      if statement.Sid == "WriteObjects"
      ]) && alltrue([
      for name in ["download", "extraction"] : alltrue([
        for statement in jsondecode(data.aws_iam_policy_document.lambda_storage[name].json).Statement :
        !contains(flatten([statement.Resource]), "${aws_s3_bucket.this.arn}/control/address-release/active.json") &&
        !contains(flatten([statement.Resource]), "${aws_s3_bucket.this.arn}/quarantine/*")
        if statement.Sid == "WriteObjects"
      ])
    ])
    error_message = "Payload workers must not overwrite Discovery's cursor or write Quarantine; Upload may write only its own cursor."
  }

  assert {
    condition = toset([
      for rule in aws_s3_bucket_lifecycle_configuration.this.rule : one(rule.filter).prefix
    ]) == toset(["landing/raw/", "landing/extracted/", "control/acquisitions/"])
    error_message = "Expiry must cover payloads and audit manifests, never cursors or Quarantine."
  }
}
