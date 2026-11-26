mock_provider "aws" {
  mock_data "aws_caller_identity" {
    defaults = {
      account_id = "123456789012"
    }
  }

  mock_data "aws_kms_key" {
    defaults = {
      arn = "arn:aws:kms:eu-west-2:123456789012:key/example"
    }
  }

  mock_data "aws_iam_policy_document" {
    defaults = {
      json = "{\"Version\":\"2012-10-17\",\"Statement\":[]}"
    }
  }
}

mock_provider "vault" {}

run "protects_only_landing" {
  command = plan

  variables {
    aws_profile = "development-eu-west-2"
    environment = "cidev"
    reconciliation_lambda_arns = {
      extraction = "arn:aws:lambda:eu-west-2:123456789012:function:extraction"
      import     = "arn:aws:lambda:eu-west-2:123456789012:function:import"
    }
  }

  assert {
    condition     = aws_guardduty_malware_protection_plan.landing[0].protected_resource[0].s3_bucket[0].object_prefixes == toset(["landing/"])
    error_message = "The plan must scan the entire Landing prefix, not Control."
  }

  assert {
    condition     = aws_guardduty_malware_protection_plan.landing[0].actions[0].tagging[0].status == "ENABLED"
    error_message = "GuardDuty scan-result tagging must be enabled."
  }

  assert {
    condition     = length(aws_cloudwatch_event_rule.reconciliation) == 2
    error_message = "Extraction and import need scheduled reconciliation."
  }
}

run "rejects_unrecognised_root" {
  command = plan

  variables {
    aws_profile             = "development-eu-west-2"
    environment             = "cidev"
    acquisition_root_prefix = "address-lookup/cidev"
  }

  expect_failures = [var.acquisition_root_prefix]
}
