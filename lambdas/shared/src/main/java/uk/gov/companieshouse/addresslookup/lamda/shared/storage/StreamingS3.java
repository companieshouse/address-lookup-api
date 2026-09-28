package uk.gov.companieshouse.addresslookup.lamda.shared.storage;

import software.amazon.awssdk.core.sync.RequestBody;
import software.amazon.awssdk.services.s3.S3Client;
import software.amazon.awssdk.services.s3.model.CompletedPart;
import uk.gov.companieshouse.addresslookup.lamda.shared.runtime.RuntimeSupport;

import java.io.InputStream;
import java.security.MessageDigest;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.Map;
import java.util.function.LongSupplier;
import java.util.logging.Logger;

import static uk.gov.companieshouse.addresslookup.lamda.shared.runtime.RuntimeSupport.*;

/** Bounded 8 MiB parts, with checksum validation before multipart completion. */
public final class StreamingS3 {
  private static final Logger LOG = Logger.getLogger(StreamingS3.class.getName());

  public record Uploaded(String key, String version, String sha256, String md5, long bytes) {}

  public static final class ChecksumMismatchException extends Exception {
    public ChecksumMismatchException(String expected, String actual) {
      super("OS MD5 checksum mismatch: expected=" + expected + " actual=" + actual);
    }
  }

  private final S3Client s3;
  private final LongSupplier remaining;

  public StreamingS3(S3Client s3, LongSupplier remaining) {
    this.s3 = s3;
    this.remaining = remaining;
  }

  public Uploaded put(InputStream in, String bucket, String key, String expectedMd5, long limit)
      throws Exception {
    String upload = s3.createMultipartUpload(b -> b.bucket(bucket).key(key)).uploadId();
    LOG.info("Multipart upload started bucket=" + bucket + " key=" + key);

    var parts = new ArrayList<CompletedPart>();
    var md5 = MessageDigest.getInstance("MD5");
    var sha = MessageDigest.getInstance("SHA-256");
    long total = 0;

    try {
      while (true) {
        check(
            remaining.getAsLong() > 30000,
            "Lambda deadline approaching; retry with a new object key");

        byte[] bytes = in.readNBytes(8 * 1024 * 1024);
        if (bytes.length == 0) break;

        total += bytes.length;
        check(total <= limit && parts.size() < 10000, "Object exceeds configured streaming budget");

        // Update checksums while streaming; do not read the completed object back from S3.
        md5.update(bytes);
        sha.update(bytes);

        int part = parts.size() + 1;
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
      }

      check(total > 0, "Empty object");

      String actualMd5 = HexFormat.of().formatHex(md5.digest()),
          actualSha = HexFormat.of().formatHex(sha.digest());

      // Complete the multipart upload only after proving the supplier checksum matches.
      if (expectedMd5 != null && !expectedMd5.equalsIgnoreCase(actualMd5))
        throw new ChecksumMismatchException(expectedMd5, actualMd5);

      var result =
          s3.completeMultipartUpload(
              b ->
                  b.bucket(bucket)
                      .key(key)
                      .uploadId(upload)
                      .ifNoneMatch("*")
                      .multipartUpload(m -> m.parts(parts)));
      check(
          result.versionId() != null && !result.versionId().equals("null"),
          "S3 versioning must be enabled");

      // Store immutable checksum evidence separately from the receipt used by the workflow.
      String checksumKey =
          "metadata/checksums/"
              + RuntimeSupport.sha(key.getBytes(java.nio.charset.StandardCharsets.UTF_8))
              + ".json";

      String evidence =
          JSON.writeValueAsString(
              Map.of(
                  "bucket",
                  bucket,
                  "key",
                  key,
                  "versionId",
                  result.versionId(),
                  "md5",
                  actualMd5,
                  "sha256",
                  actualSha,
                  "bytes",
                  total));

      s3.putObject(
          b -> b.bucket(bucket).key(checksumKey).ifNoneMatch("*"),
          RequestBody.fromString(evidence));

      LOG.info(
          "Multipart upload completed bucket="
              + bucket
              + " key="
              + key
              + " bytes="
              + total
              + " md5="
              + actualMd5
              + " sha256="
              + actualSha
              + " version="
              + result.versionId());

      return new Uploaded(key, result.versionId(), actualSha, actualMd5, total);
    } catch (Exception e) {
      LOG.warning(
          "Multipart upload failed bucket="
              + bucket
              + " key="
              + key
              + " reason="
              + e.getMessage());

      // Best effort cleanup: incomplete multipart uploads should not become readable objects.
      try {
        s3.abortMultipartUpload(b -> b.bucket(bucket).key(key).uploadId(upload));
      } catch (Exception ignored) {
      }

      throw e;
    }
  }
}
