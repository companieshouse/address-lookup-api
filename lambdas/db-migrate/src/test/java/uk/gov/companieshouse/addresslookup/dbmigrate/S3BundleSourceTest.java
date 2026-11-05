package uk.gov.companieshouse.addresslookup.dbmigrate;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.ArgumentMatchers;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import software.amazon.awssdk.core.ResponseInputStream;
import software.amazon.awssdk.http.AbortableInputStream;
import software.amazon.awssdk.http.Abortable;
import software.amazon.awssdk.services.s3.S3Client;
import software.amazon.awssdk.services.s3.model.GetObjectRequest;
import software.amazon.awssdk.services.s3.model.GetObjectResponse;
import software.amazon.awssdk.services.s3.model.NoSuchKeyException;
import software.amazon.awssdk.services.s3.model.S3Exception;

import java.io.ByteArrayInputStream;
import java.nio.charset.StandardCharsets;
import java.util.function.Consumer;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class S3BundleSourceTest {

  private static final String SNAPSHOT_BUCKET = "snapshot-bucket";
  private static final String SNAPSHOT_KEY = "address-lookup/db-schema-snapshots/x.zip";
  private static final String VERSION_ID = "v1";

  @Mock private S3Client s3Client;
  @Mock private Abortable abortable;

  private S3BundleSource s3BundleSource;

  @BeforeEach
  void setup() {
    s3BundleSource = new S3BundleSource(s3Client);
  }

  @Test
  void missingKeyFailsAsBundleNotFound() {
    when(s3Client.getObject(ArgumentMatchers.<Consumer<GetObjectRequest.Builder>>any()))
        .thenThrow(NoSuchKeyException.builder().build());

    var e =
        assertThrows(
            MigrationException.class, () -> s3BundleSource.open(SNAPSHOT_BUCKET, SNAPSHOT_KEY, VERSION_ID));

    assertEquals(MigrationException.Code.BUNDLE_NOT_FOUND, e.code());
    assertEquals("BUNDLE_NOT_FOUND: s3://snapshot-bucket/address-lookup/db-schema-snapshots/x.zip not found", e.getMessage());
  }

  @Test
  void unknownVersionWithStatus404FailsAsBundleNotFound() {
    when(s3Client.getObject(ArgumentMatchers.<Consumer<GetObjectRequest.Builder>>any()))
        .thenThrow(S3Exception.builder().statusCode(404).build());

    var e =
        assertThrows(
            MigrationException.class, () -> s3BundleSource.open(SNAPSHOT_BUCKET, SNAPSHOT_KEY, VERSION_ID));

    assertEquals(MigrationException.Code.BUNDLE_NOT_FOUND, e.code());
    assertEquals(
        "BUNDLE_NOT_FOUND: s3://snapshot-bucket/address-lookup/db-schema-snapshots/x.zip version v1 not found", e.getMessage());
  }

  @Test
  void unknownVersionWithStatus400FailsAsBundleNotFound() {
    when(s3Client.getObject(ArgumentMatchers.<Consumer<GetObjectRequest.Builder>>any()))
        .thenThrow(S3Exception.builder().statusCode(400).build());

    var e =
        assertThrows(
            MigrationException.class, () -> s3BundleSource.open(SNAPSHOT_BUCKET, SNAPSHOT_KEY, VERSION_ID));

    assertEquals(MigrationException.Code.BUNDLE_NOT_FOUND, e.code());
  }

  @Test
  void otherS3ErrorsAreRethrownUnchanged() {
    var accessDeniedException = (S3Exception) S3Exception.builder().statusCode(403).build();
    when(s3Client.getObject(ArgumentMatchers.<Consumer<GetObjectRequest.Builder>>any()))
        .thenThrow(accessDeniedException);

    var thrown =
        assertThrows(
            S3Exception.class, () -> s3BundleSource.open(SNAPSHOT_BUCKET, SNAPSHOT_KEY, VERSION_ID));

    assertSame(accessDeniedException, thrown);
  }

  @Test
  void returnsTheObjectStreamForTheRequestedVersion() throws Exception {
    var objectStream =
        new ResponseInputStream<>(
            GetObjectResponse.builder().build(),
            AbortableInputStream.create(new ByteArrayInputStream("zip-bytes".getBytes(StandardCharsets.UTF_8))));
    when(s3Client.getObject(ArgumentMatchers.<Consumer<GetObjectRequest.Builder>>any())).thenReturn(objectStream);

    try (var openedStream = s3BundleSource.open(SNAPSHOT_BUCKET, SNAPSHOT_KEY, VERSION_ID)) {
      assertEquals("zip-bytes", new String(openedStream.readAllBytes(), StandardCharsets.UTF_8));
    }
  }

  @Test
  void closingBeforeTheEndAbortsTheRequest() throws Exception {
    var objectStream =
        new ResponseInputStream<>(
            GetObjectResponse.builder().build(),
            AbortableInputStream.create(new ByteArrayInputStream("zip-bytes".getBytes(StandardCharsets.UTF_8)), abortable));
    when(s3Client.getObject(ArgumentMatchers.<Consumer<GetObjectRequest.Builder>>any())).thenReturn(objectStream);

    try (var openedStream = s3BundleSource.open(SNAPSHOT_BUCKET, SNAPSHOT_KEY, VERSION_ID)) {
      openedStream.read(new byte[3]);
    }

    verify(abortable).abort();
  }

  @Test
  void closingAfterTheEndDoesNotAbortTheRequest() throws Exception {
    var objectStream =
        new ResponseInputStream<>(
            GetObjectResponse.builder().build(),
            AbortableInputStream.create(new ByteArrayInputStream("zip-bytes".getBytes(StandardCharsets.UTF_8)), abortable));
    when(s3Client.getObject(ArgumentMatchers.<Consumer<GetObjectRequest.Builder>>any())).thenReturn(objectStream);

    try (var openedStream = s3BundleSource.open(SNAPSHOT_BUCKET, SNAPSHOT_KEY, VERSION_ID)) {
      openedStream.readAllBytes();
    }

    verify(abortable, never()).abort();
  }

  @Test
  void requestsTheExactBucketKeyAndVersion() {
    var objectStream =
        new ResponseInputStream<>(
            GetObjectResponse.builder().build(), AbortableInputStream.create(new ByteArrayInputStream(new byte[0])));
    when(s3Client.getObject(ArgumentMatchers.<Consumer<GetObjectRequest.Builder>>any())).thenReturn(objectStream);

    s3BundleSource.open(SNAPSHOT_BUCKET, SNAPSHOT_KEY, VERSION_ID);

    @SuppressWarnings("unchecked")
    ArgumentCaptor<Consumer<GetObjectRequest.Builder>> requestCustomiserCaptor = ArgumentCaptor.forClass(Consumer.class);
    verify(s3Client).getObject(requestCustomiserCaptor.capture());
    var requestBuilder = GetObjectRequest.builder();
    requestCustomiserCaptor.getValue().accept(requestBuilder);
    var request = requestBuilder.build();
    assertEquals(SNAPSHOT_BUCKET, request.bucket());
    assertEquals(SNAPSHOT_KEY, request.key());
    assertEquals(VERSION_ID, request.versionId());
  }
}

