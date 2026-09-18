package uk.gov.companieshouse.addresslookup.lamda.shared.storage;

import java.io.InputStream;
import java.security.*;
import java.util.*;
import java.util.function.LongSupplier;

import software.amazon.awssdk.core.sync.RequestBody;
import software.amazon.awssdk.services.s3.S3Client;
import software.amazon.awssdk.services.s3.model.*;
import uk.gov.companieshouse.addresslookup.lamda.shared.runtime.RuntimeSupport;

import static uk.gov.companieshouse.addresslookup.lamda.shared.runtime.RuntimeSupport.JSON;
import static uk.gov.companieshouse.addresslookup.lamda.shared.runtime.RuntimeSupport.check;

/**
 * Bounded 8 MiB parts, with checksum validation before multipart completion.
 */
public final class StreamingS3 {
    public record Uploaded(String key, String version, String sha256) {
    }

    private final S3Client s3;
    private final LongSupplier remaining;

    public StreamingS3(S3Client s3, LongSupplier remaining) {
        this.s3 = s3;
        this.remaining = remaining;
    }

    public Uploaded put(InputStream in, String bucket, String key, String expectedMd5, long limit) throws Exception {
        String upload = s3.createMultipartUpload(b -> b.bucket(bucket).key(key)).uploadId();
        var parts = new ArrayList<CompletedPart>();
        var md5 = MessageDigest.getInstance("MD5");
        var sha = MessageDigest.getInstance("SHA-256");
        long total = 0;
        try {
            while (true) {
                check(remaining.getAsLong() > 30000, "Lambda deadline approaching; retry with a new object key");
                byte[] bytes = in.readNBytes(8 * 1024 * 1024);
                if (bytes.length == 0) break;
                total += bytes.length;
                check(total <= limit && parts.size() < 10000, "Object exceeds configured streaming budget");
                md5.update(bytes);
                sha.update(bytes);
                int part = parts.size() + 1;
                var response = s3.uploadPart(b -> b.bucket(bucket).key(key).uploadId(upload).partNumber(part).contentLength((long) bytes.length), RequestBody.fromBytes(bytes));
                parts.add(CompletedPart.builder().partNumber(part).eTag(response.eTag()).build());
            }
            check(total > 0, "Empty object");
            String actualMd5 = HexFormat.of().formatHex(md5.digest()), actualSha = HexFormat.of().formatHex(sha.digest());
            check(expectedMd5 == null || expectedMd5.equalsIgnoreCase(actualMd5), "OS MD5 checksum mismatch");
            var result = s3.completeMultipartUpload(b -> b.bucket(bucket).key(key).uploadId(upload).ifNoneMatch("*").multipartUpload(m -> m.parts(parts)));
            check(result.versionId() != null && !result.versionId().equals("null"), "S3 versioning must be enabled");
            String checksumKey = "metadata/checksums/" + RuntimeSupport.sha(key.getBytes(java.nio.charset.StandardCharsets.UTF_8)) + ".json";
            String evidence = JSON.writeValueAsString(Map.of("bucket", bucket, "key", key, "versionId", result.versionId(), "md5", actualMd5, "sha256", actualSha, "bytes", total));
            s3.putObject(b -> b.bucket(bucket).key(checksumKey).ifNoneMatch("*"), RequestBody.fromString(evidence));
            return new Uploaded(key, result.versionId(), actualSha);
        } catch (Exception e) {
            try {
                s3.abortMultipartUpload(b -> b.bucket(bucket).key(key).uploadId(upload));
            } catch (Exception ignored) {
            }
            throw e;
        }
    }
}
