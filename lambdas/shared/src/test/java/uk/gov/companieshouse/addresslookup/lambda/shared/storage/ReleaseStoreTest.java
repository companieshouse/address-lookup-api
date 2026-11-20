package uk.gov.companieshouse.addresslookup.lambda.shared.storage;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.when;
import static uk.gov.companieshouse.addresslookup.lambda.shared.runtime.RuntimeSupport.JSON;

import java.nio.charset.StandardCharsets;
import java.io.ByteArrayInputStream;
import java.util.List;
import java.util.function.UnaryOperator;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.junit.jupiter.api.Test;
import software.amazon.awssdk.core.ResponseInputStream;
import software.amazon.awssdk.core.sync.RequestBody;
import software.amazon.awssdk.services.s3.S3Client;
import software.amazon.awssdk.services.s3.model.GetObjectRequest;
import software.amazon.awssdk.services.s3.model.GetObjectResponse;
import software.amazon.awssdk.services.s3.model.ListObjectsV2Request;
import software.amazon.awssdk.services.s3.model.ListObjectsV2Response;
import software.amazon.awssdk.services.s3.model.PutObjectRequest;
import software.amazon.awssdk.services.s3.model.PutObjectResponse;
import software.amazon.awssdk.services.s3.model.S3Exception;
import software.amazon.awssdk.services.s3.model.S3Object;
import uk.gov.companieshouse.addresslookup.lambda.shared.testsupport.FakeS3;

final class ReleaseStoreTest {
  private static final String BUCKET = "landing-bucket";
  private static final String KEY = "daily_cou/20780/150104/downloads/add_gb_royalmailaddress.zip";

  private final FakeS3 s3 = new FakeS3();
  private final ReleaseStore store = new ReleaseStore(s3.client());

  @Test
  void readReturnsNullWhenKeyDoesNotExist() throws Exception {
    assertNull(store.read(BUCKET, "missing.json"));
  }

  @Test
  void readParsesExistingJson() throws Exception {
    s3.objects.put("plan.json", "{\"releaseId\":\"abc\"}".getBytes(StandardCharsets.UTF_8));

    assertEquals("abc", store.read(BUCKET, "plan.json").path("releaseId").asText());
  }

  @Test
  void readRejectsObjectsLargerThanOneMebibyte() {
    s3.objects.put("big.json", new byte[1024 * 1024 + 1]);

    var failure =
        assertThrows(IllegalArgumentException.class, () -> store.read(BUCKET, "big.json"));

    assertEquals("Manifest exceeds 1 MiB", failure.getMessage());
  }

  @Test
  void readReturnsNullWhenObjectDisappearsBetweenListAndGet() throws Exception {
    var client = clientListingKey("plan.json");
    when(client.getObject(any(GetObjectRequest.class)))
        .thenThrow(S3Exception.builder().statusCode(404).message("gone").build());

    assertNull(new ReleaseStore(client).read(BUCKET, "plan.json"));
  }

  @Test
  void readRethrowsOtherS3Failures() {
    var client = clientListingKey("plan.json");
    when(client.getObject(any(GetObjectRequest.class)))
        .thenThrow(S3Exception.builder().statusCode(403).message("denied").build());

    var failingStore = new ReleaseStore(client);
    var failure = assertThrows(S3Exception.class, () -> failingStore.read(BUCKET, "plan.json"));

    assertEquals(403, failure.statusCode());
  }

  @Test
  void immutableWritesOnceAndIgnoresExistingObject() throws Exception {
    store.immutable(BUCKET, "receipt.json", JSON.readTree("{\"status\":\"FIRST\"}"));
    store.immutable(BUCKET, "receipt.json", JSON.readTree("{\"status\":\"SECOND\"}"));

    assertEquals("FIRST", JSON.readTree(s3.objects.get("receipt.json")).path("status").asText());
  }

  @Test
  void immutableRethrowsUnexpectedS3Failures() {
    var client = mock(S3Client.class);
    when(client.putObject(any(PutObjectRequest.class), any(RequestBody.class)))
        .thenThrow(S3Exception.builder().statusCode(500).message("boom").build());

    var failingStore = new ReleaseStore(client);
    var value = JSON.createObjectNode();
    assertThrows(S3Exception.class, () -> failingStore.immutable(BUCKET, "receipt.json", value));
  }

  @Test
  void keysListsObjectsUnderPrefix() {
    s3.objects.put("control/a.json", new byte[0]);
    s3.objects.put("control/b.json", new byte[0]);
    s3.objects.put("other/c.json", new byte[0]);

    assertEquals(List.of("control/a.json", "control/b.json"), store.keys(BUCKET, "control/"));
  }

  @Test
  void updateStatePreservesConcurrentFieldsAndRetriesConditionalConflict() throws Exception {
    String key = "initial_full/24833/150450/manifest/unpack_state.json";
    s3.objects.put(key, "{\"datasets\":{\"first\":{\"status\":\"EXTRACTED\"}}}".getBytes());
    s3.putConflicts = 1;

    var updated =
        store.updateState(
            BUCKET,
            key,
            state -> {
              state.withObject("/datasets").putObject("second").put("status", "EXTRACTED");
              return state;
            });

    assertEquals("EXTRACTED", updated.path("datasets").path("first").path("status").asText());
    assertEquals("EXTRACTED", updated.path("datasets").path("second").path("status").asText());
  }

  @Test
  void updateStateCreatesMissingState() throws Exception {
    var updated = store.updateState(BUCKET, KEY, state -> state.put("status", "EXTRACTED"));

    assertEquals(updated, JSON.readTree(s3.objects.get(KEY)));
  }

  @Test
  void updateStateBacksOffBetweenConflictsOnly() throws Exception {
    var attempts = new java.util.ArrayList<Integer>();
    var retryingStore = new ReleaseStore(s3.client(), attempts::add);
    s3.putConflicts = 2;

    retryingStore.updateState(BUCKET, KEY, state -> state.put("status", "EXTRACTED"));

    assertEquals(List.of(1, 2), attempts);
    assertEquals("EXTRACTED", JSON.readTree(s3.objects.get(KEY)).path("status").asText());
  }

  @Test
  void updateStatePreservesInterruptDuringBackoff() {
    s3.putConflicts = 1;
    Thread.currentThread().interrupt();
    try {
      var failure = assertThrows(IllegalStateException.class,
          () -> store.updateState(BUCKET, KEY, state -> state));
      assertEquals("State update retry interrupted", failure.getMessage());
      assertTrue(Thread.currentThread().isInterrupted());
    } finally {
      Thread.interrupted();
    }
  }

  @Test
  void updateStateFailsAfterRepeatedConflicts() {
    s3.putConflicts = 5;
    UnaryOperator<ObjectNode> update = state -> state.put("status", "EXTRACTED");

    var failure = assertThrows(
        IllegalStateException.class,
        () -> store.updateState(BUCKET, KEY, update));

    assertEquals("State update contention; retry invocation", failure.getMessage());
    assertFalse(s3.objects.containsKey(KEY));
  }

  @Test
  void updateStatePropagatesUnexpectedWriteFailure() {
    s3.putErrorStatus = 503;

    var failure = assertThrows(
        S3Exception.class,
        () -> store.updateState(BUCKET, KEY, state -> state));

    assertEquals(503, failure.statusCode());
  }

  @Test
  void updateStateRejectsNullUpdate() {
    var failure = assertThrows(
        IllegalArgumentException.class,
        () -> store.updateState(BUCKET, KEY, state -> null));

    assertEquals("State update returned no value", failure.getMessage());
    assertFalse(s3.objects.containsKey(KEY));
  }

  @ParameterizedTest
  @ValueSource(strings = {"[]", "null", ""})
  void updateStateRejectsNonObjectState(String value) {
    s3.objects.put(KEY, value.getBytes(StandardCharsets.UTF_8));

    var failure = assertThrows(
        IllegalArgumentException.class,
        () -> store.updateState(BUCKET, KEY, state -> state));

    assertEquals("State must be a JSON object", failure.getMessage());
  }

  @Test
  void updateStateRejectsOversizedState() {
    s3.objects.put(KEY, new byte[1024 * 1024 + 1]);

    var failure = assertThrows(
        IllegalArgumentException.class,
        () -> store.updateState(BUCKET, KEY, state -> state));

    assertEquals("State exceeds 1 MiB", failure.getMessage());
  }

  @Test
  void updateStateRetriesWhenListedStateDisappears() throws Exception {
    var client = clientListingKey(KEY);
    when(client.getObject(any(GetObjectRequest.class)))
        .thenThrow(S3Exception.builder().statusCode(404).build())
        .thenReturn(new ResponseInputStream<>(
            GetObjectResponse.builder().eTag("etag").build(),
            new ByteArrayInputStream("{}".getBytes(StandardCharsets.UTF_8))));

    var updated = new ReleaseStore(client).updateState(
        BUCKET, KEY, state -> state.put("status", "EXTRACTED"));

    assertEquals("EXTRACTED", updated.path("status").asText());
    verify(client, times(2)).getObject(any(GetObjectRequest.class));
    verify(client).putObject(
        org.mockito.ArgumentMatchers.argThat((PutObjectRequest request) ->
            "etag".equals(request.ifMatch()) && request.ifNoneMatch() == null),
        any(RequestBody.class));
  }

  @Test
  void updateStateRetriesConditionalConflictDuringCreation() throws Exception {
    var client = mock(S3Client.class);
    when(client.listObjectsV2(any(ListObjectsV2Request.class)))
        .thenReturn(ListObjectsV2Response.builder().build());
    when(client.putObject(any(PutObjectRequest.class), any(RequestBody.class)))
        .thenThrow(S3Exception.builder().statusCode(409).build())
        .thenReturn(PutObjectResponse.builder().build());

    var updated = new ReleaseStore(client).updateState(
        BUCKET, KEY, state -> state.put("status", "EXTRACTED"));

    assertEquals("EXTRACTED", updated.path("status").asText());
    verify(client, times(2)).putObject(
        org.mockito.ArgumentMatchers.argThat((PutObjectRequest request) ->
            "*".equals(request.ifNoneMatch()) && request.ifMatch() == null),
        any(RequestBody.class));
  }

  @Test
  void updateStatePropagatesUnexpectedReadFailure() {
    var client = clientListingKey(KEY);
    when(client.getObject(any(GetObjectRequest.class)))
        .thenThrow(S3Exception.builder().statusCode(403).build());
    var failingStore = new ReleaseStore(client);

    var failure = assertThrows(
        S3Exception.class,
        () -> failingStore.updateState(BUCKET, KEY, state -> state));

    assertEquals(403, failure.statusCode());
  }

  @Test
  void updateStateRequiresEtagForExistingState() {
    var client = clientListingKey(KEY);
    when(client.getObject(any(GetObjectRequest.class)))
        .thenReturn(new ResponseInputStream<>(
            GetObjectResponse.builder().build(),
            new ByteArrayInputStream("{}".getBytes(StandardCharsets.UTF_8))));
    var failingStore = new ReleaseStore(client);

    var failure = assertThrows(
        IllegalArgumentException.class,
        () -> failingStore.updateState(BUCKET, KEY, state -> state));

    assertEquals("State object is missing an ETag", failure.getMessage());
  }

  @Test
  void versionReturnsTheVersionedObjectId() {
    s3.versions.put(KEY, "abc123");

    assertEquals("abc123", store.version(BUCKET, KEY));
  }

  @Test
  void versionRejectsUnversionedBuckets() {
    s3.versions.put(KEY, "null");

    var failure = assertThrows(IllegalArgumentException.class, () -> store.version(BUCKET, KEY));

    assertEquals("Versioned bucket required", failure.getMessage());
  }

  @Test
  void scanDefaultsToPendingWhenNoTagExists() {
    assertEquals("PENDING", store.scan(BUCKET, KEY, "v1"));
    assertFalse(store.isClean(BUCKET, KEY, "v1"));
  }

  @Test
  void isCleanIsTrueOnlyForNoThreatsFound() {
    s3.scanStatus.put(KEY, ReleaseStore.CLEAN);

    assertTrue(store.isClean(BUCKET, KEY, "v1"));
  }

  @Test
  void isCleanFailsForCompletedUnsuccessfulScan() {
    s3.scanStatus.put(KEY, "THREATS_FOUND");

    var failure = assertThrows(IllegalStateException.class, () -> store.isClean(BUCKET, KEY, "v1"));

    assertTrue(failure.getMessage().contains("THREATS_FOUND"));
    assertTrue(failure.getMessage().contains(KEY));
  }

  @Test
  void cleanUsesTheVersionInTheReference() {
    s3.scanStatus.put(KEY, ReleaseStore.CLEAN);
    var ref = JSON.createObjectNode().put("s3Key", KEY).put("version", FakeS3.DEFAULT_VERSION);

    assertTrue(store.isReferenceClean(BUCKET, ref));
  }

  @Test
  void cleanFallsBackToKeyAndCurrentVersionWhenReferenceHasNeither() {
    s3.scanStatus.put(KEY, ReleaseStore.CLEAN);
    var ref = JSON.createObjectNode().put("key", KEY);

    assertTrue(store.isReferenceClean(BUCKET, ref));
  }

  @Test
  void cleanIsFalseWhileScanIsPending() {
    var ref = JSON.createObjectNode().put("s3Key", KEY);

    assertFalse(store.isReferenceClean(BUCKET, ref));
  }

  @Test
  void cleanRejectsObjectsChangedAfterCertification() {
    s3.scanStatus.put(KEY, ReleaseStore.CLEAN);
    var ref = JSON.createObjectNode().put("s3Key", KEY).put("version", "certified-version");

    var failure =
        assertThrows(IllegalArgumentException.class, () -> store.isReferenceClean(BUCKET, ref));

    assertEquals("Object changed after certification: " + KEY, failure.getMessage());
  }

  @Test
  void objectKeyPrefersS3KeyOverKey() {
    assertEquals(
        "a", ReleaseStore.objectKey(JSON.createObjectNode().put("s3Key", "a").put("key", "b")));
    assertEquals("b", ReleaseStore.objectKey(JSON.createObjectNode().put("key", "b")));
  }

  @Test
  void objectVersionUsesReferenceVersionOrLooksItUp() {
    s3.versions.put(KEY, "looked-up");

    assertEquals(
        "from-ref",
        ReleaseStore.objectVersion(
            store, BUCKET, JSON.createObjectNode().put("s3Key", KEY).put("version", "from-ref")));
    assertEquals(
        "looked-up",
        ReleaseStore.objectVersion(store, BUCKET, JSON.createObjectNode().put("s3Key", KEY)));
  }

  private static S3Client clientListingKey(String key) {
    var client = mock(S3Client.class);
    when(client.listObjectsV2(any(ListObjectsV2Request.class)))
        .thenReturn(
            ListObjectsV2Response.builder().contents(S3Object.builder().key(key).build()).build());
    return client;
  }
}
