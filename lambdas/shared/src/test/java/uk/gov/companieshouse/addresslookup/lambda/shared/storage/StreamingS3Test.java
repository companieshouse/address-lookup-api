package uk.gov.companieshouse.addresslookup.lambda.shared.storage;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import org.junit.jupiter.api.Test;

import software.amazon.awssdk.core.ResponseInputStream;
import software.amazon.awssdk.core.sync.RequestBody;
import software.amazon.awssdk.http.AbortableInputStream;
import software.amazon.awssdk.services.s3.S3Client;
import software.amazon.awssdk.services.s3.model.AbortMultipartUploadResponse;
import software.amazon.awssdk.services.s3.model.CompleteMultipartUploadResponse;
import software.amazon.awssdk.services.s3.model.CreateMultipartUploadResponse;
import software.amazon.awssdk.services.s3.model.GetObjectRequest;
import software.amazon.awssdk.services.s3.model.GetObjectResponse;
import software.amazon.awssdk.services.s3.model.HeadObjectRequest;
import software.amazon.awssdk.services.s3.model.HeadObjectResponse;
import software.amazon.awssdk.services.s3.model.ListObjectsV2Response;
import software.amazon.awssdk.services.s3.model.PutObjectResponse;
import software.amazon.awssdk.services.s3.model.S3Exception;
import software.amazon.awssdk.services.s3.model.S3Object;
import software.amazon.awssdk.services.s3.model.UploadPartResponse;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.HexFormat;
import java.util.function.Consumer;

final class StreamingS3Test {
    private static final String BUCKET = "bucket";
    private static final String KEY = "key";

    @Test
    void uploadsObjectAndStoresChecksumEvidence() throws Exception {
        var s3 = mock(S3Client.class);
        stubMultipartUpload(s3, "new-version");
        when(s3.putObject(any(Consumer.class), any(RequestBody.class)))
                .thenReturn(PutObjectResponse.builder().build());

        byte[] csv = "csv".getBytes(StandardCharsets.UTF_8);
        var uploaded =
                new StreamingS3(s3, () -> 900_000)
                        .putIdempotent(new ByteArrayInputStream(csv), BUCKET, KEY, 100);

        assertEquals(KEY, uploaded.key());
        assertEquals("new-version", uploaded.version());
        assertEquals(
                HexFormat.of().formatHex(MessageDigest.getInstance("MD5").digest(csv)),
                uploaded.md5());
        assertEquals(
                HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(csv)),
                uploaded.sha256());
        assertEquals(csv.length, uploaded.bytes());
        verify(s3).putObject(any(Consumer.class), any(RequestBody.class));
    }

    @Test
    void rejectsChecksumMismatchAndAbortsMultipartUpload() throws Exception {
        var s3 = mock(S3Client.class);
        stubMultipartUpload(s3, "new-version");

        var upload = new StreamingS3(s3, () -> 900_000);
        var source = new ByteArrayInputStream("csv".getBytes(StandardCharsets.UTF_8));

        var failure =
                assertThrows(
                        StreamingS3.ChecksumMismatchException.class,
                        () -> upload.put(source, BUCKET, KEY, "incorrect", 100));

        assertTrue(failure.getMessage().contains("OS MD5 checksum mismatch"));
        verify(s3).abortMultipartUpload(any(Consumer.class));
        verify(s3, org.mockito.Mockito.never()).completeMultipartUpload(any(Consumer.class));
    }

    @Test
    void rejectsEmptyObjectAndAbortsMultipartUpload() throws Exception {
        var s3 = mock(S3Client.class);
        stubMultipartUpload(s3, "new-version");

        var upload = new StreamingS3(s3, () -> 900_000);
        var source = new ByteArrayInputStream(new byte[0]);

        var failure =
                assertThrows(
                        IllegalArgumentException.class,
                        () -> upload.putIdempotent(source, BUCKET, KEY, 100));

        assertEquals("Empty object", failure.getMessage());
        verify(s3).abortMultipartUpload(any(Consumer.class));
    }

    @Test
    void rejectsUploadWhenLambdaDeadlineIsNear() throws Exception {
        var s3 = mock(S3Client.class);
        stubMultipartUpload(s3, "new-version");

        var upload = new StreamingS3(s3, () -> 30_000);
        var source = new ByteArrayInputStream(new byte[] {1});

        var failure =
                assertThrows(
                        IllegalArgumentException.class,
                        () -> upload.putIdempotent(source, BUCKET, KEY, 100));

        assertEquals(
                "Lambda deadline approaching; retry with a new object key", failure.getMessage());
        verify(s3).abortMultipartUpload(any(Consumer.class));
    }

    @Test
    void reusesExistingObjectWhenChecksumsMatch() throws Exception {
        var s3 = mock(S3Client.class);
        stubMultipartUpload(s3, "unused");
        when(s3.completeMultipartUpload(any(Consumer.class)))
                .thenThrow(S3Exception.builder().statusCode(412).build());
        when(s3.headObject(any(HeadObjectRequest.class)))
                .thenReturn(HeadObjectResponse.builder().versionId("existing-version").build());
        when(s3.putObject(any(Consumer.class), any(RequestBody.class)))
                .thenThrow(S3Exception.builder().statusCode(412).build());
        byte[] csv = "csv".getBytes(StandardCharsets.UTF_8);
        String sha256 = HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(csv));
        String md5 = HexFormat.of().formatHex(MessageDigest.getInstance("MD5").digest(csv));
        String checksumKey = StreamingS3.checksumKey(KEY);
        String evidence =
                "{\"bucket\":\"bucket\",\"key\":\"key\",\"versionId\":\"existing-version\","
                        + "\"md5\":\""
                        + md5
                        + "\",\"sha256\":\""
                        + sha256
                        + "\",\"bytes\":3}";
        when(s3.getObject(any(GetObjectRequest.class)))
                .thenReturn(response(csv), response(evidence.getBytes(StandardCharsets.UTF_8)));
        when(s3.listObjectsV2(
                        any(software.amazon.awssdk.services.s3.model.ListObjectsV2Request.class)))
                .thenReturn(
                        ListObjectsV2Response.builder()
                                .contents(S3Object.builder().key(checksumKey).build())
                                .build());

        var uploaded =
                new StreamingS3(s3, () -> 900_000)
                        .putIdempotent(new ByteArrayInputStream(csv), BUCKET, KEY, 100);

        assertEquals("existing-version", uploaded.version());
        verify(s3).abortMultipartUpload(any(Consumer.class));
        verify(s3).putObject(any(Consumer.class), any(RequestBody.class));
    }

    @Test
    void wrapsFailureReadingAnExistingObject() {
        var s3 = mock(S3Client.class);
        when(s3.createMultipartUpload(any(Consumer.class)))
                .thenReturn(CreateMultipartUploadResponse.builder().uploadId("upload-id").build());
        when(s3.uploadPart(any(Consumer.class), any(RequestBody.class)))
                .thenReturn(UploadPartResponse.builder().eTag("etag").build());
        when(s3.completeMultipartUpload(any(Consumer.class)))
                .thenThrow(S3Exception.builder().statusCode(412).build());
        when(s3.headObject(any(HeadObjectRequest.class)))
                .thenReturn(HeadObjectResponse.builder().versionId("existing-version").build());
        var readFailure = new IOException("read failed");
        InputStream failingStream =
                new InputStream() {
                    @Override
                    public int read() throws IOException {
                        throw readFailure;
                    }
                };
        when(s3.getObject(any(GetObjectRequest.class)))
                .thenReturn(
                        new ResponseInputStream<>(
                                GetObjectResponse.builder().build(),
                                AbortableInputStream.create(failingStream)));
        var upload = new StreamingS3(s3, () -> 900_000);
        var source = new ByteArrayInputStream("csv".getBytes(StandardCharsets.UTF_8));

        var failure =
                assertThrows(
                        StreamingS3.ExistingObjectVerificationException.class,
                        () -> upload.putIdempotent(source, "bucket", "key", 100));

        assertEquals("Could not verify existing extracted CSV", failure.getMessage());
        assertInstanceOf(IOException.class, failure.getCause());
        assertEquals(readFailure, failure.getCause());
    }

    private static void stubMultipartUpload(S3Client s3, String version) {
        when(s3.createMultipartUpload(any(Consumer.class)))
                .thenReturn(CreateMultipartUploadResponse.builder().uploadId("upload-id").build());
        when(s3.uploadPart(any(Consumer.class), any(RequestBody.class)))
                .thenReturn(UploadPartResponse.builder().eTag("etag").build());
        when(s3.completeMultipartUpload(any(Consumer.class)))
                .thenReturn(CompleteMultipartUploadResponse.builder().versionId(version).build());
        when(s3.abortMultipartUpload(any(Consumer.class)))
                .thenReturn(AbortMultipartUploadResponse.builder().build());
    }

    private static ResponseInputStream<GetObjectResponse> response(byte[] content) {
        return new ResponseInputStream<>(
                GetObjectResponse.builder().build(),
                AbortableInputStream.create(new ByteArrayInputStream(content)));
    }
}
