package uk.gov.companieshouse.addresslookup.lambda.shared.storage;

import static uk.gov.companieshouse.addresslookup.lambda.shared.runtime.RuntimeSupport.JSON;
import static uk.gov.companieshouse.addresslookup.lambda.shared.runtime.RuntimeSupport.check;

import java.io.IOException;
import software.amazon.awssdk.core.sync.RequestBody;
import software.amazon.awssdk.services.s3.S3Client;
import software.amazon.awssdk.services.s3.model.HeadObjectRequest;
import software.amazon.awssdk.services.s3.model.PutObjectRequest;
import software.amazon.awssdk.services.s3.model.S3Exception;

/** Replaces stale checksum evidence only after the pinned source matches the recorded bytes. */
final class RecoverMissing implements ExistingObjectPolicy {
    private final S3Client s3;
    private final ReleaseStore store;
    private final String previousVersion;
    private final String sha256;
    private final long bytes;
    private final boolean allowExisting;

    RecoverMissing(S3Client s3, ReleaseStore store, String previousVersion,
                   String sha256, long bytes, boolean allowExisting) {
        this.s3 = s3;
        this.store = store;
        this.previousVersion = previousVersion;
        this.sha256 = sha256;
        this.bytes = bytes;
        this.allowExisting = allowExisting;
    }

    @Override
    public String resolveObjectConflict(ObjectTarget target, String uploadId, String actualSha256,
                                        S3Exception conflict)
            throws StreamingS3.ExistingObjectVerificationException {
        if (!allowExisting) throw conflict;
        return ExistingObjectPolicy.reuseIfIdentical(s3, store)
                .resolveObjectConflict(target, uploadId, actualSha256, conflict);
    }

    @Override
    public void resolveEvidenceConflict(ObjectTarget target, String checksumKey,
                                        String evidence, S3Exception conflict)
            throws IOException {
        var recorded = store.read(target.bucket(), checksumKey);
        check(recorded != null, "Missing checksum evidence during recovery: " + checksumKey);
        if (JSON.readTree(evidence).equals(recorded)) return;
        check(target.bucket().equals(recorded.path("bucket").asText())
                && target.key().equals(recorded.path("key").asText())
                && previousVersion.equals(recorded.path("versionId").asText())
                && sha256.equals(recorded.path("sha256").asText())
                && bytes == recorded.path("bytes").asLong(-1),
            "Existing checksum evidence changed during recovery: " + checksumKey);
        var head = s3.headObject(HeadObjectRequest.builder()
            .bucket(target.bucket()).key(checksumKey).build());
        check(head.eTag() != null, "Checksum evidence has no ETag: " + checksumKey);
        s3.putObject(PutObjectRequest.builder().bucket(target.bucket()).key(checksumKey)
                .ifMatch(head.eTag()).build(), RequestBody.fromString(evidence));
    }
}
