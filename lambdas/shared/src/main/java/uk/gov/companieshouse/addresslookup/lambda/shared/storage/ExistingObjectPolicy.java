package uk.gov.companieshouse.addresslookup.lambda.shared.storage;

import software.amazon.awssdk.services.s3.S3Client;
import software.amazon.awssdk.services.s3.model.S3Exception;

import java.io.IOException;

/** Controls overwrite behaviour and handling of object or checksum evidence conflicts. */
interface ExistingObjectPolicy {
    default boolean overwritesExisting() {
        return false;
    }

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
        return new FailOnConflict(false);
    }

    /** Overwrite at the same key; unexpected conditional conflicts still fail the invocation. */
    static ExistingObjectPolicy overwrite() {
        return new FailOnConflict(true);
    }

    /** Existing objects are accepted only when identical to the freshly streamed bytes. */
    static ExistingObjectPolicy reuseIfIdentical(S3Client s3, ReleaseStore releaseStore) {
        return new ReuseIfIdentical(s3, releaseStore);
    }
}
