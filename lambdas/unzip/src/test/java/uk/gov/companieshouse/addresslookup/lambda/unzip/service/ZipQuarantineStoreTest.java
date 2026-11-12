package uk.gov.companieshouse.addresslookup.lambda.unzip.service;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import software.amazon.awssdk.services.s3.S3Client;
import software.amazon.awssdk.services.s3.model.CopyObjectRequest;
import software.amazon.awssdk.services.s3.model.CopyPartResult;
import software.amazon.awssdk.services.s3.model.CreateMultipartUploadResponse;
import software.amazon.awssdk.services.s3.model.HeadObjectRequest;
import software.amazon.awssdk.services.s3.model.HeadObjectResponse;
import software.amazon.awssdk.services.s3.model.S3Exception;
import software.amazon.awssdk.services.s3.model.UploadPartCopyRequest;
import software.amazon.awssdk.services.s3.model.UploadPartCopyResponse;

import java.util.function.Consumer;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.argThat;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
final class ZipQuarantineStoreTest {
    private static final String BUCKET = "source";
    private static final String KEY = "Scanned/release/dataset.zip";
    private static final String VERSION = "zip-version";
    private static final String DESTINATION = "quarantine/release/dataset/zip.zip";
    private static final long MAX_ZIP_BYTES = 20L * 1024 * 1024 * 1024;

    @Mock
    private S3Client s3;
    private ZipQuarantineStore quarantine;

    @BeforeEach
    void setUp() {
        quarantine = new ZipQuarantineStore(s3, MAX_ZIP_BYTES);
    }

    @Test
    void copiesExactZipVersionIntoQuarantine() {
        zipSize(1024);
        when(s3.copyObject(any(Consumer.class))).thenAnswer(call -> {
            var builder = CopyObjectRequest.builder();
            Consumer<CopyObjectRequest.Builder> request = call.getArgument(0);
            request.accept(builder);
            var copy = builder.build();
            assertEquals(BUCKET, copy.sourceBucket());
            assertEquals(KEY, copy.sourceKey());
            assertEquals(VERSION, copy.sourceVersionId());
            assertEquals(BUCKET, copy.destinationBucket());
            assertEquals(DESTINATION, copy.destinationKey());
            assertEquals("*", copy.ifNoneMatch());
            return null;
        });

        quarantine.copy(BUCKET, KEY, VERSION, DESTINATION);

        verify(s3).headObject(argThat((HeadObjectRequest request) ->
                BUCKET.equals(request.bucket()) && KEY.equals(request.key())
                        && VERSION.equals(request.versionId())));
        verify(s3, never()).createMultipartUpload(any(Consumer.class));
    }

    @Test
    void failsIfQuarantineCopyFails() {
        zipSize(1024);
        var failure = S3Exception.builder().statusCode(503).build();
        when(s3.copyObject(any(Consumer.class))).thenThrow(failure);

        assertEquals(failure, assertThrows(S3Exception.class,
                () -> quarantine.copy(BUCKET, KEY, VERSION, DESTINATION)));
    }

    @Test
    void copiesLargeZipInVersionedParts() {
        zipSize(5_000_000_001L);
        when(s3.createMultipartUpload(any(Consumer.class))).thenReturn(
                CreateMultipartUploadResponse.builder().uploadId("quarantine-upload").build());
        when(s3.uploadPartCopy(any(Consumer.class))).thenAnswer(call -> {
            var builder = UploadPartCopyRequest.builder();
            Consumer<UploadPartCopyRequest.Builder> request = call.getArgument(0);
            request.accept(builder);
            var part = builder.build();
            assertEquals(BUCKET, part.sourceBucket());
            assertEquals(KEY, part.sourceKey());
            assertEquals(VERSION, part.sourceVersionId());
            assertEquals(BUCKET, part.destinationBucket());
            assertEquals(DESTINATION, part.destinationKey());
            assertTrue(part.copySourceRange().startsWith("bytes="));
            return UploadPartCopyResponse.builder()
                    .copyPartResult(CopyPartResult.builder().eTag("etag").build()).build();
        });

        quarantine.copy(BUCKET, KEY, VERSION, DESTINATION);

        verify(s3, times(10)).uploadPartCopy(any(Consumer.class));
        verify(s3).completeMultipartUpload(any(Consumer.class));
        verify(s3, never()).copyObject(any(Consumer.class));
    }

    @Test
    void abortsIncompleteMultipartQuarantineCopy() {
        zipSize(5_000_000_001L);
        when(s3.createMultipartUpload(any(Consumer.class))).thenReturn(
                CreateMultipartUploadResponse.builder().uploadId("quarantine-upload").build());
        var failure = S3Exception.builder().statusCode(503).build();
        when(s3.uploadPartCopy(any(Consumer.class))).thenThrow(failure);

        assertEquals(failure, assertThrows(S3Exception.class,
                () -> quarantine.copy(BUCKET, KEY, VERSION, DESTINATION)));
        verify(s3).abortMultipartUpload(any(Consumer.class));
        verify(s3, never()).completeMultipartUpload(any(Consumer.class));
    }

    private void zipSize(long bytes) {
        when(s3.headObject(any(HeadObjectRequest.class)))
                .thenReturn(HeadObjectResponse.builder().contentLength(bytes).build());
    }
}
