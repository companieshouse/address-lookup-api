package uk.gov.companieshouse.addresslookup.lambda.shared.storage;

import software.amazon.awssdk.services.s3.model.S3Exception;

final class FailOnConflict implements ExistingObjectPolicy {
  @Override
  public String resolveObjectConflict(
      ObjectTarget target, String uploadId, String sha256, S3Exception conflict) {
    throw conflict;
  }

  @Override
  public void resolveEvidenceConflict(
      ObjectTarget target, String checksumKey, String evidence, S3Exception conflict) {
    throw conflict;
  }
}
