package uk.gov.companieshouse.addresslookup.lambda.unzip.service;

import software.amazon.awssdk.services.s3.S3Client;
import software.amazon.awssdk.services.s3.model.CompletedPart;
import software.amazon.awssdk.services.s3.model.HeadObjectRequest;
import software.amazon.awssdk.services.s3.model.S3Exception;
import uk.gov.companieshouse.addresslookup.lambda.shared.storage.ReleaseStore;

import java.util.ArrayList;

import static uk.gov.companieshouse.addresslookup.lambda.shared.runtime.RuntimeSupport.check;

final class ZipQuarantineStore {
    private static final long MAX_SINGLE_COPY_BYTES = 5_000_000_000L;
    private static final long COPY_PART_BYTES = 512L * 1024 * 1024;

    private final S3Client s3;
    private final long maxZipBytes;

    ZipQuarantineStore(S3Client s3, long maxZipBytes) {
        this.s3 = s3;
        this.maxZipBytes = maxZipBytes;
    }

    void copy(String bucket, String key, String version, String destination) {
        Long length = s3.headObject(HeadObjectRequest.builder()
                .bucket(bucket).key(key).versionId(version).build()).contentLength();
        check(length != null && length > 0 && length <= maxZipBytes,
                "Invalid ZIP size for quarantine");
        if (length <= MAX_SINGLE_COPY_BYTES) {
            try {
                s3.copyObject(b -> b.sourceBucket(bucket).sourceKey(key).sourceVersionId(version)
                        .destinationBucket(bucket).destinationKey(destination).ifNoneMatch("*"));
            } catch (S3Exception e) {
                if (!ReleaseStore.isPreconditionFailed(e)) throw e;
            }
            return;
        }

        String upload = s3.createMultipartUpload(b -> b.bucket(bucket).key(destination)).uploadId();
        var parts = new ArrayList<CompletedPart>();
        try {
            for (long offset = 0; offset < length; offset += COPY_PART_BYTES) {
                long start = offset;
                long end = Math.min(length - 1, offset + COPY_PART_BYTES - 1);
                int number = parts.size() + 1;
                var copied = s3.uploadPartCopy(b -> b.sourceBucket(bucket).sourceKey(key)
                        .sourceVersionId(version).copySourceRange("bytes=" + start + "-" + end)
                        .destinationBucket(bucket).destinationKey(destination)
                        .uploadId(upload).partNumber(number));
                parts.add(CompletedPart.builder().partNumber(number)
                        .eTag(copied.copyPartResult().eTag()).build());
            }
            s3.completeMultipartUpload(b -> b.bucket(bucket).key(destination).uploadId(upload)
                    .ifNoneMatch("*").multipartUpload(m -> m.parts(parts)));
        } catch (RuntimeException failure) {
            try {
                s3.abortMultipartUpload(b -> b.bucket(bucket).key(destination).uploadId(upload));
            } catch (RuntimeException abortFailure) {
                failure.addSuppressed(abortFailure);
            }
            if (!(failure instanceof S3Exception e && ReleaseStore.isPreconditionFailed(e))) {
                throw failure;
            }
        }
    }
}
