package uk.gov.companieshouse.addresslookup.lambda.shared.storage;

import static uk.gov.companieshouse.addresslookup.lambda.shared.runtime.RuntimeSupport.JSON;
import static uk.gov.companieshouse.addresslookup.lambda.shared.runtime.RuntimeSupport.check;

import java.io.IOException;
import java.io.OutputStream;
import java.security.DigestInputStream;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;
import software.amazon.awssdk.services.s3.S3Client;
import software.amazon.awssdk.services.s3.model.GetObjectRequest;
import software.amazon.awssdk.services.s3.model.HeadObjectRequest;
import software.amazon.awssdk.services.s3.model.S3Exception;

/** Idempotent retry behaviour: an already-stored object is reused if its content matches. */
final class ReuseIfIdentical implements ExistingObjectPolicy {
  private final S3Client s3;
  private final ReleaseStore releaseStore;

  ReuseIfIdentical(S3Client s3, ReleaseStore releaseStore) {
    this.s3 = s3;
    this.releaseStore = releaseStore;
  }

  @Override
  public String resolveObjectConflict(
      ObjectTarget target, String uploadId, String sha256, S3Exception conflict)
      throws StreamingS3.ExistingObjectVerificationException {
    s3.abortMultipartUpload(b -> b.bucket(target.bucket()).key(target.key()).uploadId(uploadId));
    String version =
        StreamingS3.requireVersioned(
            s3.headObject(
                    HeadObjectRequest.builder().bucket(target.bucket()).key(target.key()).build())
                .versionId());
    check(
        sha256.equals(sha256OfExisting(target, version)),
        "Existing extracted CSV differs from the source ZIP");
    return version;
  }

  @Override
  public void resolveEvidenceConflict(
      ObjectTarget target, String checksumKey, String evidence, S3Exception conflict)
      throws IOException {
    var recorded = releaseStore.read(target.bucket(), checksumKey);
    check(
        recorded != null && JSON.readTree(evidence).equals(recorded),
        "Existing checksum evidence differs from the extracted CSV");
  }

  private String sha256OfExisting(ObjectTarget target, String version)
      throws StreamingS3.ExistingObjectVerificationException {
    try {
      var digest = MessageDigest.getInstance(StreamingS3.SHA_256);
      try (var existing =
          new DigestInputStream(
              s3.getObject(
                  GetObjectRequest.builder()
                      .bucket(target.bucket())
                      .key(target.key())
                      .versionId(version)
                      .build()),
              digest)) {
        existing.transferTo(OutputStream.nullOutputStream());
      }
      return HexFormat.of().formatHex(digest.digest());
    } catch (IOException | NoSuchAlgorithmException e) {
      throw new StreamingS3.ExistingObjectVerificationException(e);
    }
  }
}
