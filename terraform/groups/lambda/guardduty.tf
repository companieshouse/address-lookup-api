# GuardDuty Malware Protection is the reason the pipeline exists in two halves.
# It tags each new object with GuardDutyMalwareScanStatus, and the Lambdas
# refuse to move content on until that tag reads NO_THREATS_FOUND. Without
# these plans nothing is ever tagged, ReleaseStore.scan() returns "PENDING"
# forever, and the pipeline stalls silently after the first download.

resource "aws_iam_role" "guardduty_malware_protection" {
  name               = "${var.environment}-${local.name_prefix}-guardduty-mp-role"
  assume_role_policy = data.aws_iam_policy_document.guardduty_malware_protection_trust.json

  tags = merge(module.iac_tags.tags, module.owner_tags.tags)
}

resource "aws_iam_role_policy" "guardduty_malware_protection" {
  name   = "${var.environment}-${local.name_prefix}-guardduty-mp-policy"
  role   = aws_iam_role.guardduty_malware_protection.id
  policy = data.aws_iam_policy_document.guardduty_malware_protection.json
}

# Only the prefixes the pipeline actually writes are scanned, so the control
# and manifest JSON objects do not attract a per-object scanning charge.
resource "aws_guardduty_malware_protection_plan" "source" {
  role = aws_iam_role.guardduty_malware_protection.arn

  protected_resource {
    s3_bucket {
      bucket_name     = aws_s3_bucket.source.id
      object_prefixes = ["quarantine/"]
    }
  }

  actions {
    tagging {
      status = "ENABLED"
    }
  }

  tags = merge(module.iac_tags.tags, module.owner_tags.tags)

  depends_on = [aws_iam_role_policy.guardduty_malware_protection]
}

# The extracted CSV is scanned again on the way out of the red zone: the unzip
# Lambda writes it to the scanned bucket, and ScanResultsService will not
# publish a ready marker until that copy is certified in its own right.
resource "aws_guardduty_malware_protection_plan" "scanned" {
  role = aws_iam_role.guardduty_malware_protection.arn

  protected_resource {
    s3_bucket {
      bucket_name     = aws_s3_bucket.scanned.id
      object_prefixes = ["quarantine/"]
    }
  }

  actions {
    tagging {
      status = "ENABLED"
    }
  }

  tags = merge(module.iac_tags.tags, module.owner_tags.tags)

  depends_on = [aws_iam_role_policy.guardduty_malware_protection]
}
