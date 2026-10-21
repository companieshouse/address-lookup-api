package uk.gov.companieshouse.addresslookup.lambda.shared.storage;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
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

import static uk.gov.companieshouse.addresslookup.lambda.shared.runtime.RuntimeSupport.*;

/**
 * Bounded 256 MiB parts, with checksum validation before multipart completion.
 */
public final class StreamingS3 {
    private static final Logger LOG = LoggerFactory.getLogger(StreamingS3.class);

    private static final int PART_SIZE_BYTES = 256 * 1024 * 1024;

    public record Uploaded(String key, String version, String sha256, String md5, long bytes) {
    }

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
        LOG.info("Multipart upload started bucket={} key={}", bucket, key);

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

                int nextPart = parts.size() + 1;
                long readStarted = System.nanoTime();
                byte[] bytes = in.readNBytes(PART_SIZE_BYTES);
                if (bytes.length == 0) break;

                total += bytes.length;
                check(total <= limit && parts.size() < 10000, "Object exceeds configured streaming budget");

                // Update checksums while streaming; do not read the completed object back from S3.
                md5.update(bytes);
                sha.update(bytes);

                final int part = parts.size() + 1;
                currentPart = part;
                stage = "uploading part";
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
            }

            check(total > 0, "Empty object");
            stage = "completing multipart upload";
            String actualMd5 = HexFormat.of().formatHex(md5.digest()),
                    actualSha = HexFormat.of().formatHex(sha.digest());

            // Complete the multipart upload only after proving the supplier checksum matches.
            if (expectedMd5 != null && !expectedMd5.equalsIgnoreCase(actualMd5))
                throw new ChecksumMismatchException(expectedMd5, actualMd5);
            try {
                long completionStarted = System.nanoTime();
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
            // Store immutable checksum evidence separately from the receipt used by the workflow.
            String checksumKey = checksumKey(key);

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

            try {
                s3.putObject(
                        b -> b.bucket(bucket).key(checksumKey).ifNoneMatch("*"),
                        RequestBody.fromString(evidence));

                LOG.info(
                        "Multipart upload completed bucket={} key={} bytes={} md5={} sha256={} version={}",
                        bucket, key, total, actualMd5, actualSha, result.versionId());

            } catch (S3Exception e) {
                if (!reuseExisting || !ReleaseStore.isPreconditionFailed(e)) throw e;
                var recorded = new ReleaseStore(s3).read(bucket, checksumKey);
                check(recorded != null && JSON.readTree(evidence).equals(recorded),
                        "Existing checksum evidence differs from the extracted CSV");
            }
            return new Uploaded(key, result.versionId(), actualSha, actualMd5, total);
        } catch (Exception e) {
            abortIncompleteUpload(bucket, key, upload, e.getMessage());
            throw e;
        }
    }

    // Best effort cleanup: incomplete multipart uploads should not become readable objects.
    private void abortIncompleteUpload(String bucket, String key, String uploadId, String reason) {
        LOG.warn("Multipart upload failed bucket={} key={} reason={}", bucket, key, reason);

        try {
            s3.abortMultipartUpload(b -> b.bucket(bucket).key(key).uploadId(uploadId));
            LOG.info("Incomplete multipart upload cleaned up bucket={} key={}", bucket, key);
        } catch (Exception abortFailure) {
            LOG.warn("Incomplete multipart upload cleanup failed bucket={} key={} reason={}",
                    bucket, key, abortFailure.getMessage());
        }
    }
  }
}
