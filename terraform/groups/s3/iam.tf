data "aws_iam_policy_document" "lambda_trust" {
  statement {
    actions = ["sts:AssumeRole"]

    principals {
      type        = "Service"
      identifiers = ["lambda.amazonaws.com"]
    }
  }
}

resource "aws_iam_role" "lambda" {
  for_each = local.lambda_access

  name               = "${local.name_prefix}-${each.key}"
  assume_role_policy = data.aws_iam_policy_document.lambda_trust.json
  tags               = local.tags
}

data "aws_iam_policy_document" "lambda_storage" {
  for_each = local.lambda_access

  statement {
    sid       = "ReadObjects"
    actions   = ["s3:GetObject", "s3:GetObjectVersion"]
    resources = [for key in each.value.read : "${aws_s3_bucket.this.arn}/${key}"]
  }

  statement {
    sid       = "WriteObjects"
    actions   = ["s3:PutObject", "s3:AbortMultipartUpload"]
    resources = [for key in each.value.write : "${aws_s3_bucket.this.arn}/${key}"]
  }

  dynamic "statement" {
    for_each = each.key == "quarantine" ? [1] : []

    content {
      sid       = "CopyQuarantineTags"
      actions   = ["s3:PutObjectTagging"]
      resources = ["${aws_s3_bucket.this.arn}/quarantine/*"]
    }
  }

  dynamic "statement" {
    for_each = length(each.value.list) > 0 ? [each.value.list] : []

    content {
      sid       = "ListPermittedPrefixes"
      actions   = ["s3:ListBucket"]
      resources = [aws_s3_bucket.this.arn]

      condition {
        test     = "StringLike"
        variable = "s3:prefix"
        values   = statement.value
      }
    }
  }

  dynamic "statement" {
    for_each = length(each.value.scan) > 0 ? [each.value.scan] : []

    content {
      sid       = "ReadScanEvidence"
      actions   = ["s3:GetObjectTagging", "s3:GetObjectVersionTagging"]
      resources = [for key in statement.value : "${aws_s3_bucket.this.arn}/${key}"]
    }
  }
}

resource "aws_iam_role_policy" "lambda_storage" {
  for_each = local.lambda_access

  name   = "acquisition-storage"
  role   = aws_iam_role.lambda[each.key].id
  policy = data.aws_iam_policy_document.lambda_storage[each.key].json
}

data "aws_iam_policy_document" "break_glass_storage" {
  statement {
    sid       = "ReadQuarantineVersions"
    actions   = ["s3:GetObject", "s3:GetObjectVersion"]
    resources = ["${aws_s3_bucket.this.arn}/quarantine/*"]
  }

  statement {
    sid       = "ListQuarantineVersions"
    actions   = ["s3:ListBucket", "s3:ListBucketVersions"]
    resources = [aws_s3_bucket.this.arn]

    condition {
      test     = "StringLike"
      variable = "s3:prefix"
      values   = ["quarantine/*"]
    }
  }
}

resource "aws_iam_policy" "break_glass_storage" {
  name   = "${local.name_prefix}-quarantine-read"
  policy = data.aws_iam_policy_document.break_glass_storage.json
  tags   = local.tags
}
