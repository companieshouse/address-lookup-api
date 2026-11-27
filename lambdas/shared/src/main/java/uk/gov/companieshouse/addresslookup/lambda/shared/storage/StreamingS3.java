package uk.gov.companieshouse.addresslookup.lambda.shared.storage;

import static uk.gov.companieshouse.addresslookup.lambda.shared.runtime.RuntimeSupport.JSON;
import static uk.gov.companieshouse.addresslookup.lambda.shared.runtime.RuntimeSupport.check;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.function.LongSupplier;
import software.amazon.awssdk.core.sync.RequestBody;
import software.amazon.awssdk.services.s3.S3Client;
import software.amazon.awssdk.services.s3.model.CompletedPart;
import software.amazon.awssdk.services.s3.model.S3Exception;
import uk.gov.companieshouse.addresslookup.lambda.shared.util.KeyLayout;
import uk.gov.companieshouse.logging.Logger;
import uk.gov.companieshouse.logging.LoggerFactory;

/** Bounded multipart uploads, with checksum validation before completion. */
public final class StreamingS3 {
  private static final Logger LOG = LoggerFactory.getLogger(StreamingS3.class.getName());

  static final String BUCKET_FIELD = "bucket";
  static final String KEY_FIELD = "key";
  static final String SHA_256 = "SHA-256";
  private static final int DEFAULT_PART_SIZE_BYTES = 256 * 1024 * 1024;
  private static final int PARALLEL_PART_SIZE_BYTES = 64 * 1024 * 1024;
  private static final int PARALLEL_UPLOADS = 4;
  private static final int MAX_PARTS = 10_000;
  private static final long DEADLINE_MARGIN_MILLIS = 30_000;
  private static final String S3_VERSIONING_MESSAGE = "S3 versioning must be enabled";
  private static final String DEADLINE_MESSAGE =
      "Lambda deadline approaching; retry with a new object key";

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

  @FunctionalInterface
  public interface BeforeCompletion {
    void run() throws IOException;
  }

  public record PartProgress(
      int partNumber,
      long bytes,
      long readNanos,
      long digestNanos,
      long uploadNanos,
      long elapsedNanos) {}

  @FunctionalInterface
  public interface ProgressListener {
    void onPart(PartProgress progress);
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
      throws IOException,
          NoSuchAlgorithmException,
          ChecksumMismatchException,
          ExistingObjectVerificationException {
    var verifier =
        expectedMd5 == null ? ChecksumVerifier.none() : ChecksumVerifier.md5(expectedMd5);
    return put(
        in,
        new ObjectTarget(bucket, key),
        limit,
        verifier,
        ExistingObjectPolicy.failOnConflict(),
        null,
        null);
  }

  public Uploaded putIdempotent(InputStream in, String bucket, String key, long limit)
      throws IOException,
          NoSuchAlgorithmException,
          ChecksumMismatchException,
          ExistingObjectVerificationException {
    return put(
        in,
        new ObjectTarget(bucket, key),
        limit,
        ChecksumVerifier.none(),
        reuseExisting,
        null,
        null);
  }

  public Uploaded putIdempotentWithProgress(
      InputStream in, String bucket, String key, long limit, ProgressListener progress)
      throws IOException,
          NoSuchAlgorithmException,
          ChecksumMismatchException,
          ExistingObjectVerificationException {
    return put(
        in,
        new ObjectTarget(bucket, key),
        limit,
        ChecksumVerifier.none(),
        reuseExisting,
        null,
        progress);
  }

  public Uploaded putRecoverMissingWithProgress(
      InputStream in,
      String bucket,
      String key,
      long limit,
      String previousVersion,
      String expectedSha256,
      long expectedBytes,
      boolean allowExisting,
      ProgressListener progress)
      throws IOException,
          NoSuchAlgorithmException,
          ChecksumMismatchException,
          ExistingObjectVerificationException {
    var target = new ObjectTarget(bucket, key);
    return put(
        in,
        target,
        limit,
        ChecksumVerifier.none(),
        new RecoverMissing(
            s3,
            new ReleaseStore(s3),
            previousVersion,
            expectedSha256,
            expectedBytes,
            allowExisting),
        null,
        progress,
        new RecoveryExpectation(expectedSha256, expectedBytes));
  }

  public Uploaded putIdempotent(
      InputStream in, String bucket, String key, long limit, BeforeCompletion beforeCompletion)
      throws IOException,
          NoSuchAlgorithmException,
          ChecksumMismatchException,
          ExistingObjectVerificationException {
    return put(
        in,
        new ObjectTarget(bucket, key),
        limit,
        ChecksumVerifier.none(),
        reuseExisting,
        beforeCompletion,
        null);
  }

  public Uploaded putOverwrite(InputStream in, String bucket, String key, long limit)
      throws IOException,
          NoSuchAlgorithmException,
          ChecksumMismatchException,
          ExistingObjectVerificationException {
    return putOverwrite(in, bucket, key, null, limit);
  }

  public Uploaded putOverwrite(
      InputStream in, String bucket, String key, String expectedMd5, long limit)
      throws IOException,
          NoSuchAlgorithmException,
          ChecksumMismatchException,
          ExistingObjectVerificationException {
    return put(
        in,
        new ObjectTarget(bucket, key),
        limit,
        expectedMd5 == null ? ChecksumVerifier.none() : ChecksumVerifier.md5(expectedMd5),
        ExistingObjectPolicy.overwrite(),
        null,
        null);
  }

  public static String checksumKey(String key) {
    return KeyLayout.checksumKey(key);
  }

  private Uploaded put(
      InputStream in,
      ObjectTarget target,
      long limit,
      ChecksumVerifier verifier,
      ExistingObjectPolicy policy,
      BeforeCompletion beforeCompletion,
      ProgressListener progress)
      throws IOException,
          NoSuchAlgorithmException,
          ChecksumMismatchException,
          ExistingObjectVerificationException {
    return put(in, target, limit, verifier, policy, beforeCompletion, progress, null);
  }

  private record RecoveryExpectation(String sha256, long bytes) {}

  private Uploaded put(
      InputStream in,
      ObjectTarget target,
      long limit,
      ChecksumVerifier verifier,
      ExistingObjectPolicy policy,
      BeforeCompletion beforeCompletion,
      ProgressListener progress,
      RecoveryExpectation expectation)
      throws IOException,
          NoSuchAlgorithmException,
          ChecksumMismatchException,
          ExistingObjectVerificationException {
    String uploadId =
        s3.createMultipartUpload(b -> b.bucket(target.bucket()).key(target.key())).uploadId();
    LOG.info("Multipart upload started", target.logFields());

    try {
      var streamed = uploadParts(in, target, uploadId, limit, progress);

      if (expectation != null) {
        check(
            expectation.bytes() == streamed.bytes()
                && expectation.sha256().equals(streamed.sha256()),
            "Recovered ZIP member differs from recorded checksum: " + target.key());
      }
      // Complete the multipart upload only after proving the supplier checksum matches.
      verifier.verify(streamed.md5());
      if (beforeCompletion != null) {
        beforeCompletion.run();
      }
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

  /**
   * Streams the input into bounded parts, hashing as it goes; never re-reads the object from S3.
   */
  private StreamedParts uploadParts(
      InputStream in, ObjectTarget target, String uploadId, long limit, ProgressListener progress)
      throws IOException, NoSuchAlgorithmException {
    if (progress != null) {
      return uploadPartsConcurrent(in, target, uploadId, limit, progress);
    }
    var parts = new ArrayList<CompletedPart>();
    var md5 = md5ForIntegrity();
    var sha256 = MessageDigest.getInstance(SHA_256);
    long total = 0;

    while (true) {
      check(remaining.getAsLong() > DEADLINE_MARGIN_MILLIS, DEADLINE_MESSAGE);

      byte[] bytes = in.readNBytes(partSizeBytes);
      if (bytes.length == 0) {
        break;
      }
      total += bytes.length;
      check(
          total <= limit && parts.size() < MAX_PARTS, "Object exceeds configured streaming budget");

      md5.update(bytes);
      sha256.update(bytes);
      parts.add(uploadPart(target, uploadId, parts.size() + 1, bytes));
    }

    check(total > 0, "Empty object");
    return new StreamedParts(
        parts,
        total,
        HexFormat.of().formatHex(md5.digest()),
        HexFormat.of().formatHex(sha256.digest()));
  }

  private record UploadedPart(CompletedPart part, long uploadNanos) {}

  private record PendingPart(
      Future<UploadedPart> future, int number, long bytes, long readNanos, long digestNanos) {}

  private StreamedParts uploadPartsConcurrent(
      InputStream in, ObjectTarget target, String uploadId, long limit, ProgressListener progress)
      throws IOException, NoSuchAlgorithmException {
    var executor = Executors.newFixedThreadPool(PARALLEL_UPLOADS);
    var pending = new ArrayDeque<PendingPart>();
    var parts = new ArrayList<CompletedPart>();
    var md5 = md5ForIntegrity();
    var sha256 = MessageDigest.getInstance(SHA_256);
    long total = 0;
    long readNanos = 0;
    long digestNanos = 0;
    long uploadNanos = 0;
    long startedAt = System.nanoTime();
    boolean finished = false;
    try {
      while (true) {
        if (pending.size() == PARALLEL_UPLOADS) {
          uploadNanos += collectPart(pending.peekFirst(), parts, progress, uploadNanos, startedAt);
          pending.removeFirst();
        }
        check(remaining.getAsLong() > DEADLINE_MARGIN_MILLIS, DEADLINE_MESSAGE);

        long started = System.nanoTime();
        byte[] bytes = in.readNBytes(Math.min(partSizeBytes, PARALLEL_PART_SIZE_BYTES));
        readNanos += System.nanoTime() - started;
        if (bytes.length == 0) break;
        total += bytes.length;
        check(
            total <= limit && parts.size() + pending.size() < MAX_PARTS,
            "Object exceeds configured streaming budget");

        started = System.nanoTime();
        md5.update(bytes);
        sha256.update(bytes);
        digestNanos += System.nanoTime() - started;
        int number = parts.size() + pending.size() + 1;
        var part =
            executor.submit(() -> {
              long uploadStarted = System.nanoTime();
              var completed = uploadPart(target, uploadId, number, bytes);
              return new UploadedPart(completed, System.nanoTime() - uploadStarted);
            });
        pending.addLast(new PendingPart(part, number, total, readNanos, digestNanos));
      }
      while (!pending.isEmpty()) {
        uploadNanos += collectPart(pending.peekFirst(), parts, progress, uploadNanos, startedAt);
        pending.removeFirst();
      }
      check(total > 0, "Empty object");
      finished = true;
      return new StreamedParts(
          parts,
          total,
          HexFormat.of().formatHex(md5.digest()),
          HexFormat.of().formatHex(sha256.digest()));
    } finally {
      if (!finished) {
        pending.forEach(part -> part.future().cancel(true));
        executor.shutdownNow();
        try {
          executor.awaitTermination(5, TimeUnit.SECONDS);
        } catch (InterruptedException _) {
          Thread.currentThread().interrupt();
        }
      } else {
        executor.shutdown();
      }
    }
  }

  private long collectPart(
      PendingPart pending,
      List<CompletedPart> parts,
      ProgressListener progress,
      long uploadNanos,
      long startedAt)
      throws IOException {
    try {
      long waitMillis = remaining.getAsLong() - DEADLINE_MARGIN_MILLIS;
      check(waitMillis > 0, DEADLINE_MESSAGE);
      UploadedPart uploaded = pending.future().get(waitMillis, TimeUnit.MILLISECONDS);
      parts.add(uploaded.part());
      long cumulativeUploadNanos = uploadNanos + uploaded.uploadNanos();
      progress.onPart(
          new PartProgress(
              pending.number(),
              pending.bytes(),
              pending.readNanos(),
              pending.digestNanos(),
              cumulativeUploadNanos,
              System.nanoTime() - startedAt));
      return uploaded.uploadNanos();
    } catch (InterruptedException interrupted) {
      Thread.currentThread().interrupt();
      throw new IOException("Multipart upload interrupted", interrupted);
    } catch (TimeoutException timeout) {
      throw new IOException("Multipart upload deadline approaching", timeout);
    } catch (ExecutionException failed) {
      if (failed.getCause() instanceof RuntimeException runtime) throw runtime;
      if (failed.getCause() instanceof Error error) throw error;
      throw new IOException("Multipart upload part failed", failed.getCause());
    }
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
            // An upload may retry the body; reuse the immutable part without copying it.
            RequestBody.fromContentProvider(
                () -> new ByteArrayInputStream(bytes), bytes.length, "application/octet-stream"));
    return CompletedPart.builder().partNumber(partNumber).eTag(response.eTag()).build();
  }

  private String completeUpload(
      ObjectTarget target, String uploadId, StreamedParts streamed, ExistingObjectPolicy policy)
      throws ExistingObjectVerificationException {
    try {
      var result =
          policy.overwritesExisting()
              ? s3.completeMultipartUpload(b ->
                  b.bucket(target.bucket())
                      .key(target.key())
                      .uploadId(uploadId)
                      .multipartUpload(m -> m.parts(streamed.parts())))
              : s3.completeMultipartUpload(b ->
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

  /** Stores checksum evidence separately from the uploaded object. */
  private void saveChecksumEvidence(
      ObjectTarget target, Uploaded uploaded, ExistingObjectPolicy policy) throws IOException {
    String checksumKey = checksumKey(target.key());
    String evidence = JSON.writeValueAsString(evidenceOf(target, uploaded));
    try {
      if (policy.overwritesExisting()) {
        s3.putObject(
            b -> b.bucket(target.bucket()).key(checksumKey), RequestBody.fromString(evidence));
      } else {
        s3.putObject(
            b -> b.bucket(target.bucket()).key(checksumKey).ifNoneMatch("*"),
            RequestBody.fromString(evidence));
      }
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
        BUCKET_FIELD,
        target.bucket(),
        KEY_FIELD,
        target.key(),
        "versionId",
        uploaded.version(),
        "md5",
        uploaded.md5(),
        "sha256",
        uploaded.sha256(),
        "bytes",
        uploaded.bytes());
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

  private static MessageDigest md5ForIntegrity() throws NoSuchAlgorithmException {
    // MD5 verifies the supplier checksum; it is not used for security.
    return MessageDigest.getInstance("MD5"); // NOSONAR java:S4790
  }
}
