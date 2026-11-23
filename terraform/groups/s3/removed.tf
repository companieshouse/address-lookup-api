# Relinquish ownership without deleting previously deployed buckets or their settings.
removed {
  from = aws_s3_bucket.this
  lifecycle {
    destroy = false
  }
}

removed {
  from = aws_s3_bucket_logging.this
  lifecycle {
    destroy = false
  }
}

removed {
  from = module.access_logging
  lifecycle {
    destroy = false
  }
}

removed {
  from = aws_s3_bucket_versioning.this
  lifecycle {
    destroy = false
  }
}

removed {
  from = aws_s3_bucket_public_access_block.this
  lifecycle {
    destroy = false
  }
}

removed {
  from = aws_s3_bucket_ownership_controls.this
  lifecycle {
    destroy = false
  }
}

removed {
  from = aws_s3_bucket_server_side_encryption_configuration.this
  lifecycle {
    destroy = false
  }
}

removed {
  from = aws_s3_bucket_policy.this
  lifecycle {
    destroy = false
  }
}

removed {
  from = aws_s3_bucket_lifecycle_configuration.landing
  lifecycle {
    destroy = false
  }
}

removed {
  from = aws_s3_bucket_lifecycle_configuration.control
  lifecycle {
    destroy = false
  }
}

removed {
  from = aws_s3_bucket_lifecycle_configuration.quarantine
  lifecycle {
    destroy = false
  }
}
