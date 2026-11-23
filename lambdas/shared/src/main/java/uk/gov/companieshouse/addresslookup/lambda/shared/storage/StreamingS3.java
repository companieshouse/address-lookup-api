package uk.gov.companieshouse.addresslookup.lambda.shared.storage;

import static uk.gov.companieshouse.addresslookup.lambda.shared.runtime.RuntimeSupport.JSON;
import static uk.gov.companieshouse.addresslookup.lambda.shared.runtime.RuntimeSupport.check;
import software.amazon.awssdk.core.sync.RequestBody;
import software.amazon.awssdk.services.s3.S3Client;
import software.amazon.awssdk.services.s3.model.CompletedPart;
import software.amazon.awssdk.services.s3.model.S3Exception;
import uk.gov.companieshouse.addresslookup.lambda.shared.runtime.RuntimeSupport;
import uk.gov.companieshouse.logging.Logger;
import uk.gov.companieshouse.logging.LoggerFactory;

import java.io.IOException;
import java.io.InputStream;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.List;
import java.util.Map;
import java.util.function.LongSupplier;

/**
 * Bounded 256 MiB parts, with checksum validation before multipart completion.
 */
public final class StreamingS3 {
  private static final Logger LOG = LoggerFactory.getLogger(StreamingS3.class.getName());

  static final String BUCKET_FIELD = "bucket";
  static final String KEY_FIELD = "key";
  static final String SHA_256 = "SHA-256";
  private static final int DEFAULT_PART_SIZE_BYTES = 256 * 1024 * 1024;
  private static final int MAX_PARTS = 10_000;
  private static final long DEADLINE_MARGIN_MILLIS = 30_000;
  private static final String S3_VERSIONING_MESSAGE = "S3 versioning must be enabled";

  public record Uploaded(String key, String version, String sha256, String md5, long bytes) {}

  public static final class ChecksumMismatchException extends Exception {
    public ChecksumMismatchException(String expected, String actual) {
      super("OS MD5 checksum mismatch: expected=" + expected + " actual=" + actual);
    }
  }

  public static final class ExistingObjectVerificationException extends Exception {
    public ExistingObjectVerificationException(Throwable cause) {
      super("Could not verify existing extracted CSV", cause);
    }
  }

  private final S3Client s3;
  private final LongSupplier remaining;
  private final int partSizeBytes;
  private final ExistingObjectPolicy reuseExisting;

  public StreamingS3(S3Client s3, LongSupplier remaining) {
    this(s3, remaining, new ReleaseStore(s3), DEFAULT_PART_SIZE_BYTES);
  }

  StreamingS3(S3Client s3, LongSupplier remaining, ReleaseStore releaseStore, int partSizeBytes) {
    this.s3 = s3;
    this.remaining = remaining;
    this.partSizeBytes = partSizeBytes;
    this.reuseExisting = ExistingObjectPolicy.reuseIfIdentical(s3, releaseStore);
  }

  public Uploaded put(InputStream in, String bucket, String key, String expectedMd5, long limit)
      throws IOException, NoSuchAlgorithmException, ChecksumMismatchException,
          ExistingObjectVerificationException {
    var verifier = expectedMd5 == null ? ChecksumVerifier.none() : ChecksumVerifier.md5(expectedMd5);
    return put(in, new ObjectTarget(bucket, key), limit, verifier, ExistingObjectPolicy.failOnConflict());
  }

  public Uploaded putIdempotent(InputStream in, String bucket, String key, long limit)
      throws IOException, NoSuchAlgorithmException, ChecksumMismatchException,
          ExistingObjectVerificationException {
    return put(in, new ObjectTarget(bucket, key), limit, ChecksumVerifier.none(), reuseExisting);
  }

  public static String checksumKey(String key) {
    return "metadata/checksums/"
        + RuntimeSupport.sha(key.getBytes(java.nio.charset.StandardCharsets.UTF_8))
        + ".json";
  }

  private Uploaded put(
      InputStream in,
      ObjectTarget target,
      long limit,
      ChecksumVerifier verifier,
      ExistingObjectPolicy policy)
      throws IOException, NoSuchAlgorithmException, ChecksumMismatchException,
          ExistingObjectVerificationException {
    String uploadId =
        s3.createMultipartUpload(b -> b.bucket(target.bucket()).key(target.key())).uploadId();
    LOG.info("Multipart upload started", target.logFields());

    try {
      var streamed = uploadParts(in, target, uploadId, limit);

      // Complete the multipart upload only after proving the supplier checksum matches.
      verifier.verify(streamed.md5());
      String version = completeUpload(target, uploadId, streamed, policy);

      var uploaded =
          new Uploaded(target.key(), version, streamed.sha256(), streamed.md5(), streamed.bytes());
      saveChecksumEvidence(target, uploaded, policy);
      return uploaded;
    } catch (Exception e) {
      abortIncompleteUpload(target, uploadId, e);
      throw e;
    }
  }

  /** Streams the input into bounded parts, hashing as it goes; never re-reads the object from S3. */
  private StreamedParts uploadParts(InputStream in, ObjectTarget target, String uploadId, long limit)
      throws IOException, NoSuchAlgorithmException {
    var parts = new ArrayList<CompletedPart>();
    var digests = new Digests();
    long total = 0;

    while (true) {
      check(
          remaining.getAsLong() > DEADLINE_MARGIN_MILLIS,
          "Lambda deadline approaching; retry with a new object key");

      byte[] bytes = in.readNBytes(partSizeBytes);
      if (bytes.length == 0) {
        break;
      }
      total += bytes.length;
      check(
          total <= limit && parts.size() < MAX_PARTS,
          "Object exceeds configured streaming budget");

      digests.update(bytes);
      parts.add(uploadPart(target, uploadId, parts.size() + 1, bytes));
    }

    check(total > 0, "Empty object");
    return new StreamedParts(parts, total, digests.md5Hex(), digests.sha256Hex());
  }

  private CompletedPart uploadPart(
      ObjectTarget target, String uploadId, int partNumber, byte[] bytes) {
    var response =
        s3.uploadPart(
            b ->
                b.bucket(target.bucket())
                    .key(target.key())
                    .uploadId(uploadId)
                    .partNumber(partNumber)
                    .contentLength((long) bytes.length),
            RequestBody.fromBytes(bytes));
    return CompletedPart.builder().partNumber(partNumber).eTag(response.eTag()).build();
  }

  private String completeUpload(
      ObjectTarget target, String uploadId, StreamedParts streamed, ExistingObjectPolicy policy)
      throws ExistingObjectVerificationException {
    try {
      var result =
          s3.completeMultipartUpload(
              b ->
                  b.bucket(target.bucket())
                      .key(target.key())
                      .uploadId(uploadId)
                      .ifNoneMatch("*")
                      .multipartUpload(m -> m.parts(streamed.parts())));
      return requireVersioned(result.versionId());
    } catch (S3Exception e) {
      if (!ReleaseStore.isPreconditionFailed(e)) {
        throw e;
      }
      return policy.resolveObjectConflict(target, uploadId, streamed.sha256(), e);
    }
  }

  /** Stores immutable checksum evidence separately from the receipt used by the workflow. */
  private void saveChecksumEvidence(
      ObjectTarget target, Uploaded uploaded, ExistingObjectPolicy policy) throws IOException {
    String checksumKey = checksumKey(target.key());
    String evidence = JSON.writeValueAsString(evidenceOf(target, uploaded));
    try {
      s3.putObject(
          b -> b.bucket(target.bucket()).key(checksumKey).ifNoneMatch("*"),
          RequestBody.fromString(evidence));
      LOG.info(
          "Multipart upload completed",
          Map.of(
              BUCKET_FIELD,
              target.bucket(),
              KEY_FIELD,
              uploaded.key(),
              "bytes",
              uploaded.bytes(),
              "version",
              uploaded.version()));
    } catch (S3Exception e) {
      if (!ReleaseStore.isPreconditionFailed(e)) {
        throw e;
      }
      policy.resolveEvidenceConflict(target, checksumKey, evidence, e);
    }
  }

  private static Map<String, Object> evidenceOf(ObjectTarget target, Uploaded uploaded) {
    return Map.of(
        BUCKET_FIELD, target.bucket(),
        KEY_FIELD, target.key(),
        "versionId", uploaded.version(),
        "md5", uploaded.md5(),
        "sha256", uploaded.sha256(),
        "bytes", uploaded.bytes());
  }

  static String requireVersioned(String version) {
    check(version != null && !"null".equals(version), S3_VERSIONING_MESSAGE);
    return version;
  }

  // Best effort cleanup: incomplete multipart uploads should not become readable objects.
  private void abortIncompleteUpload(ObjectTarget target, String uploadId, Exception failure) {
    LOG.error("Multipart upload failed", failure, target.logFields());
    try {
      s3.abortMultipartUpload(b -> b.bucket(target.bucket()).key(target.key()).uploadId(uploadId));
      LOG.info("Incomplete multipart upload cleaned up", target.logFields());
    } catch (Exception abortFailure) {
      LOG.error("Incomplete multipart upload cleanup failed", abortFailure, target.logFields());
    }
  }

  private record StreamedParts(List<CompletedPart> parts, long bytes, String md5, String sha256) {}

  /** Computes both digests in a single pass over the streamed bytes. */
  private static final class Digests {
    private final MessageDigest md5;
    private final MessageDigest sha256;

    Digests() throws NoSuchAlgorithmException {
      // MD5 only verifies download integrity against the checksum published by OS; not used for security.
      this.md5 = MessageDigest.getInstance("MD5"); // NOSONAR java:S4790
      this.sha256 = MessageDigest.getInstance(SHA_256);
    }

    void update(byte[] bytes) {
      md5.update(bytes);
      sha256.update(bytes);
    }

    String md5Hex() {
      return HexFormat.of().formatHex(md5.digest());
    }

    String sha256Hex() {
        return HexFormat.of().formatHex(sha256.digest());
    }
  }
}
