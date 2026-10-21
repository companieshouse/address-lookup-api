package uk.gov.companieshouse.addresslookup.lambda.shared.storage;

import software.amazon.awssdk.core.sync.RequestBody;
import software.amazon.awssdk.services.s3.S3Client;
import software.amazon.awssdk.services.s3.model.CompletedPart;
import uk.gov.companieshouse.addresslookup.lambda.shared.runtime.RuntimeSupport;

import java.io.InputStream;
import java.io.OutputStream;
import java.security.DigestInputStream;
import java.security.MessageDigest;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.Map;
import java.util.concurrent.TimeUnit;
import java.util.function.LongSupplier;
import software.amazon.awssdk.services.s3.model.GetObjectRequest;
import software.amazon.awssdk.services.s3.model.HeadObjectRequest;
import software.amazon.awssdk.services.s3.model.S3Exception;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import static uk.gov.companieshouse.addresslookup.lambda.shared.runtime.RuntimeSupport.*;

/** Bounded 32 MiB parts, with checksum validation before multipart completion. */
public final class StreamingS3 {
  private static final Logger LOGGER = LoggerFactory.getLogger(StreamingS3.class);
  private static final int PART_BYTES = 32 * 1024 * 1024;

  public record Uploaded(String key, String version, String sha256) {}

  private final S3Client s3;
  private final LongSupplier remaining;

  public StreamingS3(S3Client s3, LongSupplier remaining) {
    this.s3 = s3;
    this.remaining = remaining;
  }

  public Uploaded put(InputStream in, String bucket, String key, String expectedMd5, long limit)
      throws Exception {
    return put(in, bucket, key, expectedMd5, limit, false);
  }

  public Uploaded putIdempotent(InputStream in, String bucket, String key, long limit)
      throws Exception {
    return put(in, bucket, key, null, limit, true);
  }

  public static String checksumKey(String key) throws Exception {
    return "metadata/checksums/"
        + RuntimeSupport.sha(key.getBytes(java.nio.charset.StandardCharsets.UTF_8)) + ".json";
  }

  private Uploaded put(
      InputStream in, String bucket, String key, String expectedMd5, long limit,
      boolean reuseExisting) throws Exception {
    String upload = s3.createMultipartUpload(b -> b.bucket(bucket).key(key)).uploadId();
    LOGGER.info("Created multipart upload id={} bucket={}", upload, bucket);
    var parts = new ArrayList<CompletedPart>();
    var md5 = MessageDigest.getInstance("MD5");
    var sha = MessageDigest.getInstance("SHA-256");
    long total = 0;
    int currentPart = 0;
    String stage = "reading part";
    try {
      while (true) {
        check(
            remaining.getAsLong() > 30000,
            "Lambda deadline approaching; retry with a new object key");
        LOGGER.info("Remaining Lambda time (ms): {}", remaining.getAsLong());
        int nextPart = parts.size() + 1;
        LOGGER.info("Reading part {} remainingMs={}", nextPart, remaining.getAsLong());
        long readStarted = System.nanoTime();
        byte[] bytes = in.readNBytes(PART_BYTES);
        LOGGER.info("Read part {} bytes={} elapsedMs={} remainingMs={}",
                    nextPart, bytes.length,
                    TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - readStarted),
                    remaining.getAsLong());
        if (bytes.length == 0) break;
        total += bytes.length;
        check(total <= limit && parts.size() < 10000, "Object exceeds configured streaming budget");
        md5.update(bytes);
        sha.update(bytes);
        final int part = parts.size() + 1;
        currentPart = part;
        stage = "uploading part";
        LOGGER.info("Uploading part {} for key: {} bytes={} total={}", part, key, bytes.length, total);
        long uploadStarted = System.nanoTime();
        var response =
            s3.uploadPart(
                b ->
                    b.bucket(bucket)
                        .key(key)
                        .uploadId(upload)
                        .partNumber(part)
                        .contentLength((long) bytes.length),
                RequestBody.fromBytes(bytes));
        parts.add(CompletedPart.builder().partNumber(part).eTag(response.eTag()).build());
        LOGGER.info("Completed upload of part {} for key: {} elapsedMs={} remainingMs={}", part, key,
                    TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - uploadStarted),
                    remaining.getAsLong());
      }
      check(total > 0, "Empty object");
      stage = "completing multipart upload";
      String actualMd5 = HexFormat.of().formatHex(md5.digest()),
          actualSha = HexFormat.of().formatHex(sha.digest());
      check(
          expectedMd5 == null || expectedMd5.equalsIgnoreCase(actualMd5),
          "OS MD5 checksum mismatch");
      String version;
      try {
        LOGGER.info("Completing multipart upload parts={} remainingMs={}",
          parts.size(), remaining.getAsLong());
        long completionStarted = System.nanoTime();
        var result =
            s3.completeMultipartUpload(
                b ->
                    b.bucket(bucket)
                        .key(key)
                        .uploadId(upload)
                        .ifNoneMatch("*")
                        .multipartUpload(m -> m.parts(parts)));
        LOGGER.info("Completed multipart upload elapsedMs={} remainingMs={}",
          TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - completionStarted),
          remaining.getAsLong());
        version = result.versionId();
      } catch (S3Exception e) {
        if (!reuseExisting || !ReleaseStore.isPreconditionFailed(e)) throw e;
        s3.abortMultipartUpload(b -> b.bucket(bucket).key(key).uploadId(upload));
        version = s3.headObject(
            HeadObjectRequest.builder().bucket(bucket).key(key).build()).versionId();
        check(version != null && !"null".equals(version), "S3 versioning must be enabled");
        var existingSha = MessageDigest.getInstance("SHA-256");
        try (var existing =
            new DigestInputStream(
                s3.getObject(
                    GetObjectRequest.builder().bucket(bucket).key(key).versionId(version).build()),
                existingSha)) {
          existing.transferTo(OutputStream.nullOutputStream());
        }
        check(actualSha.equals(HexFormat.of().formatHex(existingSha.digest())),
            "Existing extracted CSV differs from the source ZIP");
      }
      check(
          version != null && !"null".equals(version),
          "S3 versioning must be enabled");
      stage = "writing checksum evidence";
      String checksumKey = checksumKey(key);
      String evidence =
          JSON.writeValueAsString(
              Map.of(
                  "bucket",
                  bucket,
                  "key",
                  key,
                  "versionId",
                  version,
                  "md5",
                  actualMd5,
                  "sha256",
                  actualSha,
                  "bytes",
                  total));
      try {
        s3.putObject(
            b -> b.bucket(bucket).key(checksumKey).ifNoneMatch("*"),
            RequestBody.fromString(evidence));
      } catch (S3Exception e) {
        if (!reuseExisting || !ReleaseStore.isPreconditionFailed(e)) throw e;
        var recorded = new ReleaseStore(s3).read(bucket, checksumKey);
        check(recorded != null && JSON.readTree(evidence).equals(recorded),
            "Existing checksum evidence differs from the extracted CSV");
      }
      return new Uploaded(key, version, actualSha);
    } catch (Exception e) {
      LOGGER.error(
          "Multipart upload failed stage={} part={} totalBytes={} uploadId={} bucket={} key={}",
          stage, currentPart, total, upload, bucket, key, e);
      try {
        s3.abortMultipartUpload(b -> b.bucket(bucket).key(key).uploadId(upload));
      } catch (Exception ignored) {
      }
      throw e;
    }
  }
}
