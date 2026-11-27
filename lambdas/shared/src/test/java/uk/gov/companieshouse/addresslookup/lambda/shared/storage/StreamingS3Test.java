package uk.gov.companieshouse.addresslookup.lambda.shared.storage;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Consumer;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import software.amazon.awssdk.core.ResponseInputStream;
import software.amazon.awssdk.core.sync.RequestBody;
import software.amazon.awssdk.http.AbortableInputStream;
import software.amazon.awssdk.services.s3.S3Client;
import software.amazon.awssdk.services.s3.model.AbortMultipartUploadRequest;
import software.amazon.awssdk.services.s3.model.AbortMultipartUploadResponse;
import software.amazon.awssdk.services.s3.model.CompleteMultipartUploadRequest;
import software.amazon.awssdk.services.s3.model.CompleteMultipartUploadResponse;
import software.amazon.awssdk.services.s3.model.CreateMultipartUploadRequest;
import software.amazon.awssdk.services.s3.model.CreateMultipartUploadResponse;
import software.amazon.awssdk.services.s3.model.GetObjectRequest;
import software.amazon.awssdk.services.s3.model.GetObjectResponse;
import software.amazon.awssdk.services.s3.model.HeadObjectRequest;
import software.amazon.awssdk.services.s3.model.HeadObjectResponse;
import software.amazon.awssdk.services.s3.model.ListObjectsV2Response;
import software.amazon.awssdk.services.s3.model.PutObjectRequest;
import software.amazon.awssdk.services.s3.model.PutObjectResponse;
import software.amazon.awssdk.services.s3.model.S3Exception;
import software.amazon.awssdk.services.s3.model.S3Object;
import software.amazon.awssdk.services.s3.model.UploadPartRequest;
import software.amazon.awssdk.services.s3.model.UploadPartResponse;

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
        HexFormat.of().formatHex(MessageDigest.getInstance("MD5").digest(csv)), uploaded.md5());
    assertEquals(
        HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(csv)),
        uploaded.sha256());
    assertEquals(csv.length, uploaded.bytes());
    verify(s3).putObject(any(Consumer.class), any(RequestBody.class));
  }

  @Test
  void validatesRemainingInputBeforeCompletingUpload() throws Exception {
    var s3 = mock(S3Client.class);
    stubMultipartUpload(s3, "new-version");
    when(s3.putObject(any(Consumer.class), any(RequestBody.class)))
        .thenReturn(PutObjectResponse.builder().build());

    new StreamingS3(s3, () -> 900_000)
        .putIdempotent(
            new ByteArrayInputStream("csv".getBytes(StandardCharsets.UTF_8)),
            BUCKET,
            KEY,
            100,
            () -> {
              verify(s3, never()).completeMultipartUpload(any(Consumer.class));
              verify(s3).uploadPart(any(Consumer.class), any(RequestBody.class));
            });

    verify(s3).completeMultipartUpload(any(Consumer.class));
  }

  @Test
  void abortsUploadWhenRemainingInputIsInvalid() throws Exception {
    var s3 = mock(S3Client.class);
    stubMultipartUpload(s3, "unused");
    var failure = new IOException("invalid ZIP entry");

    var thrown =
        assertThrows(
            IOException.class,
            () ->
                new StreamingS3(s3, () -> 900_000)
                    .putIdempotent(
                        new ByteArrayInputStream("csv".getBytes(StandardCharsets.UTF_8)),
                        BUCKET,
                        KEY,
                        100,
                        () -> {
                          throw failure;
                        }));

    assertSame(failure, thrown);
    verify(s3, never()).completeMultipartUpload(any(Consumer.class));
    verify(s3).abortMultipartUpload(any(Consumer.class));
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
    verify(s3, never()).completeMultipartUpload(any(Consumer.class));
  }

  @Test
  void rejectsEmptyObjectAndAbortsMultipartUpload() throws Exception {
    var s3 = mock(S3Client.class);
    stubMultipartUpload(s3, "new-version");

    var upload = new StreamingS3(s3, () -> 900_000);
    var source = new ByteArrayInputStream(new byte[0]);

    var failure =
        assertThrows(
            IllegalArgumentException.class, () -> upload.putIdempotent(source, BUCKET, KEY, 100));

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
            IllegalArgumentException.class, () -> upload.putIdempotent(source, BUCKET, KEY, 100));

    assertEquals("Lambda deadline approaching; retry with a new object key", failure.getMessage());
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
    when(s3.listObjectsV2(any(software.amazon.awssdk.services.s3.model.ListObjectsV2Request.class)))
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
                GetObjectResponse.builder().build(), AbortableInputStream.create(failingStream)));
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

  @Test
  void splitsObjectIntoBoundedPartsAndAcceptsMatchingMd5() throws Exception {
    var s3 = mock(S3Client.class);
    stubMultipartUpload(s3, "new-version");
    when(s3.putObject(any(Consumer.class), any(RequestBody.class)))
        .thenReturn(PutObjectResponse.builder().build());
    byte[] csv = "abcde".getBytes(StandardCharsets.UTF_8);
    String md5 = HexFormat.of().formatHex(MessageDigest.getInstance("MD5").digest(csv));

    var uploaded =
        new StreamingS3(s3, () -> 900_000, new ReleaseStore(s3), 2)
            .put(new ByteArrayInputStream(csv), BUCKET, KEY, md5, 100);

    assertEquals(5, uploaded.bytes());
    verify(s3, times(3)).uploadPart(any(Consumer.class), any(RequestBody.class));
  }

  @Test
  void reportsCumulativeReadDigestAndUploadTimeAfterEachPart() throws Exception {
    var s3 = mock(S3Client.class);
    stubMultipartUpload(s3, "new-version");
    when(s3.putObject(any(Consumer.class), any(RequestBody.class)))
        .thenReturn(PutObjectResponse.builder().build());
    var progress = new ArrayList<StreamingS3.PartProgress>();

    new StreamingS3(s3, () -> 900_000, new ReleaseStore(s3), 2)
        .putIdempotentWithProgress(
            new ByteArrayInputStream("abcde".getBytes(StandardCharsets.UTF_8)),
            BUCKET,
            KEY,
            100,
            progress::add);

    assertEquals(3, progress.size());
    assertEquals(2, progress.get(0).bytes());
    assertEquals(4, progress.get(1).bytes());
    assertEquals(5, progress.get(2).bytes());
    for (int i = 0; i < progress.size(); i++) {
      assertEquals(i + 1, progress.get(i).partNumber());
      assertTrue(progress.get(i).readNanos() >= 0);
      assertTrue(progress.get(i).digestNanos() >= 0);
      assertTrue(progress.get(i).uploadNanos() >= 0);
      assertTrue(progress.get(i).elapsedNanos() >= 0);
    }
  }

  @Test
  void uploadsPartsConcurrentlyButCompletesInPartOrder() throws Exception {
    var s3 = mock(S3Client.class);
    stubMultipartUpload(s3, "new-version");
    when(s3.putObject(any(Consumer.class), any(RequestBody.class)))
        .thenReturn(PutObjectResponse.builder().build());
    var started = new CountDownLatch(4);
    var release = new CountDownLatch(1);
    var active = new AtomicInteger();
    var maximum = new AtomicInteger();
    doAnswer(
            invocation -> {
              var builder = UploadPartRequest.builder();
              invocation.<Consumer<UploadPartRequest.Builder>>getArgument(0).accept(builder);
              int current = active.incrementAndGet();
              maximum.accumulateAndGet(current, Math::max);
              started.countDown();
              try {
                if (!release.await(5, TimeUnit.SECONDS)) {
                  throw new IllegalStateException("Timed out waiting for other parts");
                }
                return UploadPartResponse.builder()
                    .eTag("part-" + builder.build().partNumber())
                    .build();
              } finally {
                active.decrementAndGet();
              }
            })
        .when(s3)
        .uploadPart(any(Consumer.class), any(RequestBody.class));

    var worker = Executors.newSingleThreadExecutor();
    try {
      var progress = new ArrayList<StreamingS3.PartProgress>();
      var future =
          worker.submit(() ->
              new StreamingS3(s3, () -> 900_000, new ReleaseStore(s3), 2)
                  .putIdempotentWithProgress(
                      new ByteArrayInputStream("abcdefghij".getBytes(StandardCharsets.UTF_8)),
                      BUCKET,
                      KEY,
                      100,
                      progress::add));
      assertTrue(started.await(5, TimeUnit.SECONDS), "four parts should overlap");
      assertEquals(4, maximum.get());
      release.countDown();
      assertEquals(10, future.get(5, TimeUnit.SECONDS).bytes());
      assertEquals(5, progress.size());
      for (int i = 0; i < progress.size(); i++) {
        assertEquals(i + 1, progress.get(i).partNumber());
      }
      var complete = ArgumentCaptor.forClass(Consumer.class);
      verify(s3).completeMultipartUpload(complete.capture());
      var request = CompleteMultipartUploadRequest.builder();
      ((Consumer<CompleteMultipartUploadRequest.Builder>) complete.getValue()).accept(request);
      assertEquals(
          java.util.List.of("part-1", "part-2", "part-3", "part-4", "part-5"),
          request.build().multipartUpload().parts().stream()
              .map(software.amazon.awssdk.services.s3.model.CompletedPart::eTag)
              .toList());
    } finally {
      release.countDown();
      worker.shutdownNow();
    }
  }

  @Test
  void abortsConcurrentUploadWhenAnyPartFails() throws Exception {
    var s3 = mock(S3Client.class);
    stubMultipartUpload(s3, "unused");
    var secondStarted = new CountDownLatch(1);
    var secondCancelled = new CountDownLatch(1);
    var failure = S3Exception.builder().statusCode(503).message("part failed").build();
    doAnswer(
            invocation -> {
              var builder = UploadPartRequest.builder();
              invocation.<Consumer<UploadPartRequest.Builder>>getArgument(0).accept(builder);
              if (builder.build().partNumber() == 1) {
                assertTrue(secondStarted.await(5, TimeUnit.SECONDS));
                throw failure;
              }
              secondStarted.countDown();
              try {
                new CountDownLatch(1).await(5, TimeUnit.SECONDS);
              } catch (InterruptedException _) {
                secondCancelled.countDown();
                Thread.currentThread().interrupt();
              }
              return UploadPartResponse.builder().eTag("part-2").build();
            })
        .when(s3)
        .uploadPart(any(Consumer.class), any(RequestBody.class));

    var upload = new StreamingS3(s3, () -> 900_000, new ReleaseStore(s3), 2);
    var source = new ByteArrayInputStream("abcd".getBytes(StandardCharsets.UTF_8));
    var thrown =
        assertThrows(
            S3Exception.class,
            () -> upload.putIdempotentWithProgress(source, BUCKET, KEY, 100, progress -> {}));
    assertSame(failure, thrown);
    assertTrue(secondCancelled.await(5, TimeUnit.SECONDS));
    verify(s3).abortMultipartUpload(any(Consumer.class));
    verify(s3, never()).completeMultipartUpload(any(Consumer.class));
  }

  @Test
  void rejectsObjectExceedingStreamingBudget() {
    var s3 = mock(S3Client.class);
    stubMultipartUpload(s3, "new-version");

    var upload = new StreamingS3(s3, () -> 900_000);
    var source = new ByteArrayInputStream("abc".getBytes(StandardCharsets.UTF_8));

    var failure =
        assertThrows(
            IllegalArgumentException.class, () -> upload.putIdempotent(source, BUCKET, KEY, 2));

    assertEquals("Object exceeds configured streaming budget", failure.getMessage());
    verify(s3).abortMultipartUpload(any(Consumer.class));
  }

  @Test
  void rejectsUploadWhenVersioningIsDisabled() {
    var s3 = mock(S3Client.class);
    stubMultipartUpload(s3, "null");

    var upload = new StreamingS3(s3, () -> 900_000);
    var source = new ByteArrayInputStream("csv".getBytes(StandardCharsets.UTF_8));

    var failure =
        assertThrows(
            IllegalArgumentException.class, () -> upload.putIdempotent(source, BUCKET, KEY, 100));

    assertEquals("S3 versioning must be enabled", failure.getMessage());
  }

  @Test
  void nonIdempotentUploadFailsWhenObjectAlreadyExists() {
    var s3 = mock(S3Client.class);
    stubMultipartUpload(s3, "unused");
    var conflict = S3Exception.builder().statusCode(412).build();
    when(s3.completeMultipartUpload(any(Consumer.class))).thenThrow(conflict);

    var upload = new StreamingS3(s3, () -> 900_000);
    var source = new ByteArrayInputStream("csv".getBytes(StandardCharsets.UTF_8));

    var thrown = assertThrows(S3Exception.class, () -> upload.put(source, BUCKET, KEY, null, 100));

    assertSame(conflict, thrown);
    verify(s3, never()).headObject(any(HeadObjectRequest.class));
  }

  @Test
  void rethrowsUnexpectedCompleteFailureEvenForIdempotentUpload() {
    var s3 = mock(S3Client.class);
    stubMultipartUpload(s3, "unused");
    var serverError = S3Exception.builder().statusCode(500).build();
    when(s3.completeMultipartUpload(any(Consumer.class))).thenThrow(serverError);

    var upload = new StreamingS3(s3, () -> 900_000);
    var source = new ByteArrayInputStream("csv".getBytes(StandardCharsets.UTF_8));

    var thrown =
        assertThrows(S3Exception.class, () -> upload.putIdempotent(source, BUCKET, KEY, 100));

    assertSame(serverError, thrown);
    verify(s3, never()).headObject(any(HeadObjectRequest.class));
  }

  @Test
  void rethrowsUnexpectedEvidenceWriteFailure() {
    var s3 = mock(S3Client.class);
    stubMultipartUpload(s3, "new-version");
    var serverError = S3Exception.builder().statusCode(500).build();
    when(s3.putObject(any(Consumer.class), any(RequestBody.class))).thenThrow(serverError);

    var upload = new StreamingS3(s3, () -> 900_000);
    var source = new ByteArrayInputStream("csv".getBytes(StandardCharsets.UTF_8));

    var thrown =
        assertThrows(S3Exception.class, () -> upload.putIdempotent(source, BUCKET, KEY, 100));

    assertSame(serverError, thrown);
  }

  @Test
  void nonIdempotentUploadFailsWhenEvidenceAlreadyExists() {
    var s3 = mock(S3Client.class);
    stubMultipartUpload(s3, "new-version");
    var conflict = S3Exception.builder().statusCode(412).build();
    when(s3.putObject(any(Consumer.class), any(RequestBody.class))).thenThrow(conflict);

    var upload = new StreamingS3(s3, () -> 900_000);
    var source = new ByteArrayInputStream("csv".getBytes(StandardCharsets.UTF_8));

    var thrown = assertThrows(S3Exception.class, () -> upload.put(source, BUCKET, KEY, null, 100));

    assertSame(conflict, thrown);
  }

  @Test
  void originalFailureSurvivesAbortCleanupFailure() {
    var s3 = mock(S3Client.class);
    stubMultipartUpload(s3, "new-version");
    when(s3.abortMultipartUpload(any(Consumer.class)))
        .thenThrow(new IllegalStateException("abort"));

    var upload = new StreamingS3(s3, () -> 900_000);
    var source = new ByteArrayInputStream(new byte[0]);

    var failure =
        assertThrows(
            IllegalArgumentException.class, () -> upload.putIdempotent(source, BUCKET, KEY, 100));

    assertEquals("Empty object", failure.getMessage());
  }

  @Test
  void rejectsUploadWhenVersionIdIsMissing() {
    var s3 = mock(S3Client.class);
    stubMultipartUpload(s3, null);

    var upload = new StreamingS3(s3, () -> 900_000);
    var source = new ByteArrayInputStream("csv".getBytes(StandardCharsets.UTF_8));

    assertThrows(
        IllegalArgumentException.class, () -> upload.putIdempotent(source, BUCKET, KEY, 100));
  }

  @Test
  void rejectsObjectExceedingMaximumPartCount() {
    var s3 = mock(S3Client.class);
    stubMultipartUpload(s3, "new-version");

    var upload = new StreamingS3(s3, () -> 900_000, new ReleaseStore(s3), 1);
    var source = new ByteArrayInputStream(new byte[10_001]);

    var failure =
        assertThrows(
            IllegalArgumentException.class,
            () -> upload.putIdempotent(source, BUCKET, KEY, Long.MAX_VALUE));

    assertEquals("Object exceeds configured streaming budget", failure.getMessage());
  }

  @Test
  void addressesEveryS3RequestToTheTargetObject() throws Exception {
    var s3 = mock(S3Client.class);
    var create = ArgumentCaptor.forClass(Consumer.class);
    var part = ArgumentCaptor.forClass(Consumer.class);
    var complete = ArgumentCaptor.forClass(Consumer.class);
    var evidence = ArgumentCaptor.forClass(Consumer.class);
    var abort = ArgumentCaptor.forClass(Consumer.class);
    when(s3.createMultipartUpload(create.capture()))
        .thenReturn(CreateMultipartUploadResponse.builder().uploadId("upload-id").build());
    when(s3.uploadPart(part.capture(), any(RequestBody.class)))
        .thenReturn(UploadPartResponse.builder().eTag("etag").build());
    when(s3.completeMultipartUpload(complete.capture()))
        .thenReturn(CompleteMultipartUploadResponse.builder().versionId("v1").build());
    when(s3.putObject(evidence.capture(), any(RequestBody.class)))
        .thenReturn(PutObjectResponse.builder().build());
    new StreamingS3(s3, () -> 900_000)
        .putIdempotent(new ByteArrayInputStream(new byte[] {1}), BUCKET, KEY, 100);

    var createRequest = CreateMultipartUploadRequest.builder();
    create.getValue().accept(createRequest);
    var partRequest = UploadPartRequest.builder();
    part.getValue().accept(partRequest);
    var completeRequest = CompleteMultipartUploadRequest.builder();
    complete.getValue().accept(completeRequest);
    var evidenceRequest = PutObjectRequest.builder();
    evidence.getValue().accept(evidenceRequest);

    assertEquals(KEY, createRequest.build().key());
    assertEquals("upload-id", partRequest.build().uploadId());
    assertEquals(1, partRequest.build().partNumber());
    assertEquals("*", completeRequest.build().ifNoneMatch());
    assertEquals(StreamingS3.checksumKey(KEY), evidenceRequest.build().key());

    var failing = mock(S3Client.class);
    stubMultipartUpload(failing, "v1");
    when(failing.abortMultipartUpload(abort.capture()))
        .thenReturn(AbortMultipartUploadResponse.builder().build());
    var streaming = new StreamingS3(failing, () -> 900_000);
    var empty = new ByteArrayInputStream(new byte[0]);
    assertThrows(
        IllegalArgumentException.class, () -> streaming.putIdempotent(empty, BUCKET, KEY, 100));
    var abortRequest = AbortMultipartUploadRequest.builder();
    abort.getValue().accept(abortRequest);
    assertEquals("upload-id", abortRequest.build().uploadId());
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
