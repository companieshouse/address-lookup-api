package uk.gov.companieshouse.addresslookup.lambda.shared.storage;

import java.io.IOException;
import software.amazon.awssdk.services.s3.S3Client;
import software.amazon.awssdk.services.s3.model.S3Exception;

/**
 * Strategy for what to do when S3 reports that the object, or its checksum evidence, already
 * exists (conditional write precondition failed).
 */
interface ExistingObjectPolicy {

  /** Returns the version of the existing object to use, or rethrows {@code conflict}. */
  String resolveObjectConflict(
      ObjectTarget target, String uploadId, String sha256, S3Exception conflict)
      throws StreamingS3.ExistingObjectVerificationException;

  /** Returns normally if the existing evidence is acceptable, or rethrows {@code conflict}. */
  void resolveEvidenceConflict(
      ObjectTarget target, String checksumKey, String evidence, S3Exception conflict)
      throws IOException;

  /** Existing objects are an error. */
  static ExistingObjectPolicy failOnConflict() {
    return new FailOnConflict();
  }

  /** Existing objects are accepted only when identical to the freshly streamed bytes. */
  static ExistingObjectPolicy reuseIfIdentical(S3Client s3, ReleaseStore releaseStore) {
    return new ReuseIfIdentical(s3, releaseStore);
  }
}
