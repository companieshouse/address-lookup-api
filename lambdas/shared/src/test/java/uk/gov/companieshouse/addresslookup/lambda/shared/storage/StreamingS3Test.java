package uk.gov.companieshouse.addresslookup.lambda.shared.storage;

import org.junit.jupiter.api.Test;
import software.amazon.awssdk.core.ResponseInputStream;
import software.amazon.awssdk.core.sync.RequestBody;
import software.amazon.awssdk.http.AbortableInputStream;
import software.amazon.awssdk.services.s3.S3Client;
import software.amazon.awssdk.services.s3.model.CreateMultipartUploadResponse;
import software.amazon.awssdk.services.s3.model.GetObjectRequest;
import software.amazon.awssdk.services.s3.model.GetObjectResponse;
import software.amazon.awssdk.services.s3.model.HeadObjectRequest;
import software.amazon.awssdk.services.s3.model.HeadObjectResponse;
import software.amazon.awssdk.services.s3.model.S3Exception;
import software.amazon.awssdk.services.s3.model.UploadPartResponse;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.function.Consumer;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

final class StreamingS3Test {
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
        InputStream failingStream = new InputStream() {
            @Override
            public int read() throws IOException {
                throw readFailure;
            }
        };
        when(s3.getObject(any(GetObjectRequest.class)))
                .thenReturn(new ResponseInputStream<>(
                        GetObjectResponse.builder().build(), AbortableInputStream.create(failingStream)));
        var upload = new StreamingS3(s3, () -> 900_000);
        var source = new ByteArrayInputStream("csv".getBytes(StandardCharsets.UTF_8));

        var failure = assertThrows(StreamingS3.ExistingObjectVerificationException.class,
                () -> upload.putIdempotent(source, "bucket", "key", 100));

        assertEquals("Could not verify existing extracted CSV", failure.getMessage());
        assertInstanceOf(IOException.class, failure.getCause());
        assertEquals(readFailure, failure.getCause());
    }
}
