data "aws_rds_cluster" "acquisition_import" {
  cluster_identifier = "${var.environment}-address-rds"
}

data "aws_iam_policy_document" "aurora_import_assume_role" {
  statement {
    effect  = "Allow"
    actions = ["sts:AssumeRole"]

    principals {
      type        = "Service"
      identifiers = ["rds.amazonaws.com"]
    }

    condition {
      test     = "StringEquals"
      variable = "aws:SourceAccount"
      values   = [local.account_id]
    }

    condition {
      test     = "StringEquals"
      variable = "aws:SourceArn"
      values   = [data.aws_rds_cluster.acquisition_import.arn]
    }
  }
}

data "aws_iam_policy_document" "aurora_import" {
  statement {
    sid       = "ListLanding"
    actions   = ["s3:ListBucket"]
    resources = [aws_s3_bucket.acquisition.arn]

    condition {
      test     = "StringLike"
      variable = "s3:prefix"
      values   = ["${local.landing_prefix}*"]
    }
  }

  statement {
    sid       = "ReadLanding"
    actions   = ["s3:GetObject", "s3:GetObjectVersion"]
    resources = ["${aws_s3_bucket.acquisition.arn}/${local.landing_prefix}*"]
  }

  statement {
    sid       = "DecryptLanding"
    actions   = ["kms:Decrypt"]
    resources = [data.aws_kms_key.acquisition.arn]

    condition {
      test     = "StringEquals"
      variable = "kms:ViaService"
      values   = ["s3.${var.aws_region}.amazonaws.com"]
    }
  }
}

resource "aws_iam_role" "aurora_import" {
  name               = "address-lookup-${var.environment}-s3-import"
  assume_role_policy = data.aws_iam_policy_document.aurora_import_assume_role.json
  tags               = merge(module.iac_tags.tags, module.owner_tags.tags)
}

resource "aws_iam_role_policy" "aurora_import" {
  name   = "landing-read"
  role   = aws_iam_role.aurora_import.id
  policy = data.aws_iam_policy_document.aurora_import.json
}

resource "aws_rds_cluster_role_association" "aurora_import" {
  db_cluster_identifier = data.aws_rds_cluster.acquisition_import.cluster_identifier
  feature_name          = "s3Import"
  role_arn              = aws_iam_role.aurora_import.arn

  depends_on = [aws_iam_role_policy.aurora_import]
}
