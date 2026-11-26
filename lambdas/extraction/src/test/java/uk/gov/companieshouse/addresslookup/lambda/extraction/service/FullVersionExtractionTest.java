package uk.gov.companieshouse.addresslookup.lambda.extraction.service;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

import com.amazonaws.services.lambda.runtime.Context;
import com.fasterxml.jackson.databind.JsonNode;
import java.io.ByteArrayOutputStream;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.stream.IntStream;
import java.util.zip.CRC32;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;
import org.apache.commons.compress.archivers.zip.Zip64Mode;
import org.apache.commons.compress.archivers.zip.ZipArchiveEntry;
import org.apache.commons.compress.archivers.zip.ZipArchiveOutputStream;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import software.amazon.awssdk.core.sync.RequestBody;
import software.amazon.awssdk.services.s3.S3Client;
import software.amazon.awssdk.services.s3.model.GetObjectRequest;
import software.amazon.awssdk.services.s3.model.HeadObjectRequest;
import software.amazon.awssdk.services.s3.model.ListObjectsV2Request;
import software.amazon.awssdk.services.s3.model.ListObjectsV2Response;
import software.amazon.awssdk.services.s3.model.PutObjectRequest;
import software.amazon.awssdk.services.s3.model.S3Exception;
import software.amazon.awssdk.services.s3.model.UploadPartRequest;
import uk.gov.companieshouse.addresslookup.lambda.shared.acquisition.AcquisitionProperties;
import uk.gov.companieshouse.addresslookup.lambda.shared.acquisition.ExtractionProperties;
import uk.gov.companieshouse.addresslookup.lambda.shared.runtime.RuntimeSupport;
import uk.gov.companieshouse.addresslookup.lambda.shared.storage.StreamingS3;
import uk.gov.companieshouse.addresslookup.lambda.shared.testsupport.FakeS3;
import uk.gov.companieshouse.addresslookup.lambda.shared.testsupport.TestContext;
import uk.gov.companieshouse.release.model.DatasetCatalog;

final class FullVersionExtractionTest {
  private static final String PREFIX = "landing/full/24833/150450/";
  private static final String DATASET = "add_isl_royalmailaddress";
  private static final String ZIP = PREFIX + "downloads/" + DATASET + ".zip";
  private static final String SUMMARY = PREFIX + "downloads/" + DATASET + "_orderSummary.json";
  private static final String MANIFEST = PREFIX + "manifest/data_package_manifest.json";
  private static final String STATE = PREFIX + "manifest/unpack_state.json";
  private static final String CSV = PREFIX + "downloads/" + DATASET + "/" + DATASET + ".csv";
  private static final String OUTPUT_SUMMARY =
      PREFIX + "downloads/" + DATASET + "/" + DATASET + "_orderSummary.json";
  private static final Map<String, Object> REQUEST = Map.of("mode", "FULL",
      "versions", Map.of("data_package", "24833", "version_id", "150450"));

  private FakeS3 fake;
  private S3Client s3;
  private ExtractionComponent service;
  private Context fullContext;

  @BeforeEach
  void setUp() throws Exception {
    fake = new FakeS3();
    s3 = spy(fake.client());
    doAnswer(invocation -> {
      HeadObjectRequest request = invocation.getArgument(0);
      if (request.key().endsWith(".csv") && !fake.objects.containsKey(request.key())) {
        throw S3Exception.builder().statusCode(404).message("Not found").build();
      }
      return fake.client().headObject(request);
    }).when(s3).headObject(any(HeadObjectRequest.class));
    fullContext = TestContext.withRemainingMillis(900_000);
    service = component(List.of(policy(DATASET, 1024 * 1024)));
    fake.objects.put(ZIP, zip(Map.of(DATASET + ".csv", header() + "\n")));
    fake.objects.put(SUMMARY, "{}".getBytes(StandardCharsets.UTF_8));
    fake.versions.put(ZIP, "zip-v1");
    fake.versions.put(SUMMARY, "summary-v1");
    fake.objects.put(MANIFEST, manifest());
    fake.objects.put("control/active.json", "unchanged".getBytes(StandardCharsets.UTF_8));
  }

  private ExtractionComponent component(List<ExtractionProperties.DatasetPolicy> policies) {
    return new ExtractionComponent(s3,
        new AcquisitionProperties("control-bucket", "landing-bucket", "events", "key",
            "packages", 1024 * 1024, null, null, 1024 * 1024, 20,
            Duration.ofSeconds(20), Duration.ofSeconds(60)),
        new ExtractionProperties(
            new ExtractionProperties.Feed("24833", "control/", "landing/", policies), null));
  }

  private static ExtractionProperties.DatasetPolicy policy(String dataset, long max) {
    return policy(dataset, List.of(), List.of(), max, 99, 100, 20);
  }

  private static ExtractionProperties.DatasetPolicy policy(
      List<String> extractOnly, List<String> skip, long max) {
    return policy(DATASET, extractOnly, skip, max, 99, 100, 20);
  }

  private static ExtractionProperties.DatasetPolicy policy(
      String dataset, List<String> extractOnly, List<String> skip, long max,
      int maxEntries, int maxEntryRatio, int maxArchiveRatio) {
    return new ExtractionProperties.DatasetPolicy(dataset, extractOnly, skip, max,
        maxEntries, maxEntryRatio, maxArchiveRatio);
  }

  @ParameterizedTest
  @ValueSource(strings = {"add_gb_builtaddress", "add_gb_prebuildaddress",
      "add_isl_builtaddress", "add_isl_prebuildaddress"})
  void extractsBuiltAddressWithoutDatabaseImportMapping(String dataset) throws Exception {
    String key = PREFIX + "downloads/" + dataset + ".zip";
    fake.objects.remove(ZIP);
    fake.objects.remove(SUMMARY);
    fake.objects.put(key, zip(Map.of(dataset + ".csv",
        DatasetCatalog.extractionHeader(dataset) + "\n")));
    fake.versions.put(key, "built-v1");
    fake.scanStatus.put(key, "NO_THREATS_FOUND");
    service = component(List.of(policy(dataset, 1024 * 1024)));

    assertEquals(ExtractionResult.Status.COMPLETE, service.extract(REQUEST, fullContext).status());
    assertArrayEquals((DatasetCatalog.extractionHeader(dataset) + "\n")
            .getBytes(StandardCharsets.UTF_8),
        fake.objects.get(PREFIX + "downloads/" + dataset + "/" + dataset + ".csv"));
    assertTrue(state().path("complete").asBoolean());
  }

  @Test
  void waitsForCompletionManifestBeforeInitialisingStateThenResumes() throws Exception {
    fake.objects.remove(MANIFEST);
    fake.scanStatus.put(ZIP, "NO_THREATS_FOUND");
    fake.scanStatus.put(SUMMARY, "NO_THREATS_FOUND");

    var waiting = service.extract(REQUEST, fullContext);
    assertEquals(ExtractionResult.Status.IN_PROGRESS, waiting.status());
    assertEquals("FULL", waiting.mode());
    assertEquals("150450", waiting.version());
    assertNull(waiting.filesDone());
    assertNull(waiting.filesRemaining());
    assertEquals(List.of(), waiting.failed());
    JsonNode waitingJson = RuntimeSupport.JSON.valueToTree(waiting);
    assertEquals("IN_PROGRESS", waitingJson.path("status").asText());
    assertEquals("FULL", waitingJson.path("mode").asText());
    assertEquals("150450", waitingJson.path("version").asText());
    assertTrue(waitingJson.path("filesDone").isNull());
    assertTrue(waitingJson.path("filesRemaining").isNull());
    assertTrue(waitingJson.path("failed").isArray());
    assertFalse(fake.objects.containsKey(STATE));
    assertFalse(fake.objects.containsKey(CSV));

    fake.objects.put(MANIFEST, manifest());
    assertEquals(ExtractionResult.Status.COMPLETE, service.extract(REQUEST, fullContext).status());
    assertTrue(state().path("complete").asBoolean());
    assertTrue(fake.objects.containsKey(CSV));
  }

  @Test
  void waitsForScanThenResumesBothFiles() throws Exception {
    var waiting = service.extract(REQUEST, TestContext.withRemainingMillis(900_000));
    assertEquals(ExtractionResult.Status.IN_PROGRESS, waiting.status());
    assertEquals(0, waiting.filesDone());
    assertEquals(2, waiting.filesRemaining());
    assertFalse(fake.objects.containsKey(CSV));
    assertEquals("PENDING", state().path("files").path(ZIP).path("status").asText());
    assertEquals("zip-v1", state().path("files").path(ZIP).path("versionId").asText());
    assertEquals("summary-v1", state().path("files").path(SUMMARY).path("versionId").asText());
    assertEquals(2, state().path("files").size());

    fake.scanStatus.put(ZIP, "NO_THREATS_FOUND");
    var partial = service.extract(REQUEST, TestContext.withRemainingMillis(900_000));
    assertEquals(ExtractionResult.Status.IN_PROGRESS, partial.status());
    assertEquals(1, partial.filesDone());
    assertEquals(1, partial.filesRemaining());
    assertTrue(fake.objects.containsKey(CSV));
    verify(s3, atLeastOnce()).getObject(argThat((GetObjectRequest request) ->
        ZIP.equals(request.key()) && "zip-v1".equals(request.versionId())
            && request.range() != null));
    assertEquals("UNPACKED", state().path("files").path(ZIP).path("status").asText());
    assertEquals("PENDING", state().path("files").path(SUMMARY).path("status").asText());

    fake.scanStatus.put(SUMMARY, "NO_THREATS_FOUND");
    fake.scanStatus.put(ZIP, "THREATS_FOUND");
    var complete = service.extract(REQUEST, TestContext.withRemainingMillis(900_000));
    assertEquals(ExtractionResult.Status.COMPLETE, complete.status());
    assertEquals(2, complete.filesDone());
    assertEquals(0, complete.filesRemaining());
    assertEquals(List.of(), complete.failed());
    assertArrayEquals("{}".getBytes(StandardCharsets.UTF_8), fake.objects.get(OUTPUT_SUMMARY));
    verify(s3).getObject(argThat((GetObjectRequest request) ->
        SUMMARY.equals(request.key()) && "summary-v1".equals(request.versionId())));
    assertTrue(state().path("complete").asBoolean());
    assertEquals(CSV, state().path("files").path(ZIP).path("outputKeys").get(0).asText());
    assertEquals(OUTPUT_SUMMARY,
        state().path("files").path(SUMMARY).path("outputKeys").get(0).asText());
    var repeated = service.extract(REQUEST, TestContext.withRemainingMillis(900_000));
    assertEquals(ExtractionResult.Status.COMPLETE, repeated.status());
    assertEquals(2, repeated.filesDone());
    assertEquals(0, repeated.filesRemaining());
    assertEquals(2, fake.completedKeys.size());
    assertEquals("unchanged", new String(fake.objects.get("control/active.json"),
        StandardCharsets.UTF_8));
    verify(s3, never()).getObject(argThat((GetObjectRequest request) ->
        request.key() != null && request.key().startsWith("control/")));
  }

  @Test
  void threatStopsWithoutUnpacking() throws Exception {
    fake.scanStatus.put(ZIP, "THREATS_FOUND");
    var attention = service.extract(REQUEST, TestContext.withRemainingMillis(900_000));
    assertEquals(ExtractionResult.Status.NEEDS_ATTENTION, attention.status());
    assertEquals(0, attention.filesDone());
    assertEquals(2, attention.filesRemaining());
    assertEquals(List.of(new ExtractionResult.Failure(ZIP, "THREATS_FOUND")),
        attention.failed());
    assertEquals("PENDING", state().path("files").path(ZIP).path("status").asText());
    assertFalse(fake.objects.containsKey(CSV));
    fake.scanStatus.put(ZIP, "NO_THREATS_FOUND");
    fake.scanStatus.put(SUMMARY, "NO_THREATS_FOUND");
    assertEquals(ExtractionResult.Status.COMPLETE, service.extract(REQUEST, fullContext).status());
  }

  @Test
  void resumesFromDiscoveredFilesRatherThanIncludingNewObjects() throws Exception {
    assertEquals(ExtractionResult.Status.IN_PROGRESS, service.extract(REQUEST, fullContext).status());
    fake.objects.put(PREFIX + "downloads/unexpected.txt",
        "unexpected".getBytes(StandardCharsets.UTF_8));

    assertEquals(ExtractionResult.Status.IN_PROGRESS, service.extract(REQUEST, fullContext).status());
    assertEquals(2, state().path("files").size());
    assertEquals("zip-v1", state().path("files").path(ZIP).path("versionId").asText());
  }

  @Test
  void ignoresExistingExtractedOutputsWhenDiscoveringInputs() throws Exception {
    fake.objects.put(CSV, "previous output".getBytes(StandardCharsets.UTF_8));
    fake.objects.put(OUTPUT_SUMMARY, "{}".getBytes(StandardCharsets.UTF_8));
    fake.objects.put(PREFIX + "downloads/" + DATASET + "/" + DATASET + "_multires.csv",
        "previous output".getBytes(StandardCharsets.UTF_8));

    assertEquals(ExtractionResult.Status.IN_PROGRESS, service.extract(REQUEST, fullContext).status());
    assertEquals(2, state().path("files").size());
    assertTrue(state().path("files").has(ZIP));
    assertTrue(state().path("files").has(SUMMARY));
  }

  @Test
  void failsExplicitlyIfNewStateCannotBeReadAfterWrite() {
    doAnswer(invocation -> {
      var request = invocation.getArgument(0, ListObjectsV2Request.class);
      if (STATE.equals(request.prefix()) && fake.objects.containsKey(STATE)) {
        return ListObjectsV2Response.builder().build();
      }
      return fake.client().listObjectsV2(request);
    }).when(s3).listObjectsV2(any(ListObjectsV2Request.class));

    var failure = assertThrows(IllegalStateException.class,
        () -> service.extract(REQUEST, fullContext));
    assertTrue(failure.getMessage().contains("FULL unpack state is unreadable after write"));
    assertFalse(fake.objects.containsKey(CSV));
  }

  @Test
  void stopsBeforeStartingAnotherFileAndResumes() throws Exception {
    fake.scanStatus.put(ZIP, "NO_THREATS_FOUND");
    fake.scanStatus.put(SUMMARY, "NO_THREATS_FOUND");
    Context context = mock(Context.class);
    when(context.getRemainingTimeInMillis()).thenAnswer(
        invocation -> fake.completedKeys.isEmpty() ? 900_000 : 100_000);
    var partial = service.extract(REQUEST, context);
    assertEquals(ExtractionResult.Status.IN_PROGRESS, partial.status());
    assertEquals(1, partial.filesDone());
    assertEquals(1, partial.filesRemaining());
    assertEquals("UNPACKED", state().path("files").path(ZIP).path("status").asText());
    assertEquals("PENDING", state().path("files").path(SUMMARY).path("status").asText());
    assertEquals(ExtractionResult.Status.COMPLETE,
        service.extract(REQUEST, TestContext.withRemainingMillis(900_000)).status());
    assertEquals(2, fake.completedKeys.size());
  }

  @Test
  void failedZipRemainsPendingAndCanBeRetried() throws Exception {
    fake.scanStatus.put(ZIP, "NO_THREATS_FOUND");
    fake.objects.put(ZIP, zip(Map.of("../bad.csv", "bad")));
    assertRejected("ARCHIVE_VALIDATION_FAILED");
    assertEquals("PENDING", state().path("files").path(ZIP).path("status").asText());
    assertFalse(state().path("complete").asBoolean());
    fake.objects.put(ZIP, zip(Map.of(DATASET + ".csv", header() + "\n")));
    fake.scanStatus.put(SUMMARY, "NO_THREATS_FOUND");
    assertEquals(ExtractionResult.Status.COMPLETE, service.extract(REQUEST, fullContext).status());
  }

  @Test
  void resumesCompletedAndSkippedMembersAfterLaterMemberUploadFails() throws Exception {
    service = component(List.of(policy(List.of("_multires"), List.of("_rltenty"), 1024 * 1024)));
    fake.scanStatus.put(ZIP, "NO_THREATS_FOUND");
    fake.scanStatus.put(SUMMARY, "NO_THREATS_FOUND");
    String ancillary = PREFIX + "downloads/" + DATASET + "/" + DATASET + "_multires.csv";
    var members = new LinkedHashMap<String, String>();
    members.put(DATASET + ".csv", header() + "\n");
    members.put(DATASET + "_rltenty.csv", "skipped\n");
    members.put(DATASET + "_multires.csv", "a,b\n");
    fake.objects.put(ZIP, zip(members));

    var failOnce = new AtomicBoolean(true);
    doAnswer(invocation -> {
      if (failOnce.get() && fake.uploads.values().stream()
          .anyMatch(upload -> ancillary.equals(upload.key()))) {
        failOnce.set(false);
        throw S3Exception.builder().statusCode(503).message("upload failed").build();
      }
      return fake.client().uploadPart(
          invocation.<java.util.function.Consumer<UploadPartRequest.Builder>>getArgument(0),
          invocation.getArgument(1, RequestBody.class));
    }).when(s3).uploadPart(any(java.util.function.Consumer.class), any(RequestBody.class));

    var failure = assertThrows(S3Exception.class, () -> service.extract(REQUEST, fullContext));
    assertEquals(503, failure.statusCode());
    assertFalse(failOnce.get());
    assertArrayEquals(members.get(DATASET + ".csv").getBytes(StandardCharsets.UTF_8),
        fake.objects.get(CSV));
    assertEquals(List.of(CSV), fake.completedKeys);
    assertFalse(fake.objects.containsKey(ancillary));
    assertEquals(1, fake.abortedUploads);
    assertTrue(fake.uploads.isEmpty());
    assertEquals("PENDING", state().path("files").path(ZIP).path("status").asText());
    assertTrue(state().path("files").path(ZIP).path("outputKeys").isEmpty());
    JsonNode primary = state().path("files").path(ZIP).path("members").path(DATASET + ".csv");
    assertEquals("COMPLETE", primary.path("status").asText());
    assertEquals("LOAD", primary.path("action").asText());
    assertEquals(CSV, primary.path("outputKey").asText());
    assertEquals(fake.versions.get(CSV), primary.path("versionId").asText());
    assertEquals(members.get(DATASET + ".csv").getBytes(StandardCharsets.UTF_8).length,
        primary.path("bytes").asLong());
    assertEquals(64, primary.path("sha256").asText().length());
    assertEquals("SKIP", state().path("files").path(ZIP).path("members")
        .path(DATASET + "_rltenty.csv").path("action").asText());
    assertFalse(state().path("complete").asBoolean());

    assertEquals(ExtractionResult.Status.COMPLETE, service.extract(REQUEST, fullContext).status());
    verify(s3, atLeastOnce()).getObject(argThat((GetObjectRequest request) ->
        ZIP.equals(request.key()) && "zip-v1".equals(request.versionId())
            && request.range() != null));
    assertEquals(List.of(CSV, ancillary, OUTPUT_SUMMARY), fake.completedKeys);
    assertEquals(4, fake.uploadCounter);
    verify(s3, never()).getObject(argThat((GetObjectRequest request) ->
        CSV.equals(request.key())));
    assertArrayEquals(members.get(DATASET + ".csv").getBytes(StandardCharsets.UTF_8),
        fake.objects.get(CSV));
    assertArrayEquals("a,b\n".getBytes(StandardCharsets.UTF_8), fake.objects.get(ancillary));
    assertTrue(fake.uploads.isEmpty());
    assertEquals("UNPACKED", state().path("files").path(ZIP).path("status").asText());
    assertEquals(2, state().path("files").path(ZIP).path("outputKeys").size());
    assertEquals(CSV, state().path("files").path(ZIP).path("outputKeys").get(0).asText());
    assertEquals(ancillary, state().path("files").path(ZIP).path("outputKeys").get(1).asText());
    assertEquals(DATASET + "_rltenty.csv",
        state().path("files").path(ZIP).path("skipped").get(0).asText());
    assertTrue(state().path("complete").asBoolean());
  }

  @Test
  void adoptsValidatedOutputFromStateCreatedBeforeMemberCheckpoints() throws Exception {
    service = component(List.of(policy(List.of("_multires"), List.of(), 1024 * 1024)));
    fake.scanStatus.put(ZIP, "NO_THREATS_FOUND");
    String ancillary = PREFIX + "downloads/" + DATASET + "/" + DATASET + "_multires.csv";
    var members = new LinkedHashMap<String, String>();
    members.put(DATASET + ".csv", header() + "\n");
    members.put(DATASET + "_multires.csv", "a,b\n");
    fake.objects.put(ZIP, zip(members));
    var failOnce = new AtomicBoolean(true);
    doAnswer(invocation -> {
      if (fake.completedKeys.contains(CSV) && failOnce.getAndSet(false)) {
        throw S3Exception.builder().statusCode(503).message("upload failed").build();
      }
      return fake.client().uploadPart(
          invocation.<java.util.function.Consumer<UploadPartRequest.Builder>>getArgument(0),
          invocation.getArgument(1, RequestBody.class));
    }).when(s3).uploadPart(any(java.util.function.Consumer.class), any(RequestBody.class));

    assertThrows(S3Exception.class, () -> service.extract(REQUEST, fullContext));
    var legacyState = (com.fasterxml.jackson.databind.node.ObjectNode) state();
    ((com.fasterxml.jackson.databind.node.ObjectNode) legacyState.path("files").path(ZIP))
        .remove("members");
    fake.objects.put(STATE, RuntimeSupport.JSON.writeValueAsBytes(legacyState));
    int uploadsBeforeRetry = fake.uploadCounter;
    String evidenceKey = StreamingS3.checksumKey(CSV);
    byte[] evidence = fake.objects.get(evidenceKey);
    var changedEvidence = (com.fasterxml.jackson.databind.node.ObjectNode)
        RuntimeSupport.JSON.readTree(evidence);
    changedEvidence.put("sha256", "0".repeat(64));
    fake.objects.put(evidenceKey, RuntimeSupport.JSON.writeValueAsBytes(changedEvidence));

    var mismatch = assertThrows(IllegalArgumentException.class,
        () -> service.extract(REQUEST, fullContext));
    assertTrue(mismatch.getMessage().contains("Completed ZIP member content changed"));
    assertFalse(state().path("files").path(ZIP).has("members"));
    assertEquals(uploadsBeforeRetry, fake.uploadCounter);
    fake.objects.put(evidenceKey, evidence);

    assertEquals(ExtractionResult.Status.IN_PROGRESS, service.extract(REQUEST, fullContext).status());
    assertEquals(uploadsBeforeRetry + 1, fake.uploadCounter);
    assertEquals(List.of(CSV, ancillary), fake.completedKeys);
    assertEquals(CSV, state().path("files").path(ZIP).path("members")
        .path(DATASET + ".csv").path("outputKey").asText());
    assertEquals("UNPACKED", state().path("files").path(ZIP).path("status").asText());
  }

  @Test
  void rejectsChangedOutputVersionInMemberCheckpoint() throws Exception {
    service = component(List.of(policy(List.of("_multires"), List.of(), 1024 * 1024)));
    fake.scanStatus.put(ZIP, "NO_THREATS_FOUND");
    var members = new LinkedHashMap<String, String>();
    members.put(DATASET + ".csv", header() + "\n");
    members.put(DATASET + "_multires.csv", "a,b\n");
    fake.objects.put(ZIP, zip(members));
    var failOnce = new AtomicBoolean(true);
    doAnswer(invocation -> {
      if (fake.completedKeys.contains(CSV) && failOnce.getAndSet(false)) {
        throw S3Exception.builder().statusCode(503).message("upload failed").build();
      }
      return fake.client().uploadPart(
          invocation.<java.util.function.Consumer<UploadPartRequest.Builder>>getArgument(0),
          invocation.getArgument(1, RequestBody.class));
    }).when(s3).uploadPart(any(java.util.function.Consumer.class), any(RequestBody.class));

    assertThrows(S3Exception.class, () -> service.extract(REQUEST, fullContext));
    fake.versions.put(CSV, "replaced-version");
    var failure = assertThrows(IllegalArgumentException.class,
        () -> service.extract(REQUEST, fullContext));
    assertTrue(failure.getMessage().contains("Completed ZIP member output changed"));
    assertEquals("PENDING", state().path("files").path(ZIP).path("status").asText());

    fake.versions.put(CSV, state().path("files").path(ZIP).path("members")
        .path(DATASET + ".csv").path("versionId").asText());
    members.put(DATASET + ".csv", header() + "\nchanged\n");
    fake.objects.put(ZIP, zip(members));
    var changedContent = assertThrows(IllegalArgumentException.class,
        () -> service.extract(REQUEST, fullContext));
    assertTrue(changedContent.getMessage().contains("Completed ZIP member content changed"));
    assertEquals("PENDING", state().path("files").path(ZIP).path("status").asText());
  }

  @Test
  void recoversMissingCheckpointedOutputFromPinnedZip() throws Exception {
    service = component(List.of(policy(List.of("_multires"), List.of(), 1024 * 1024)));
    fake.scanStatus.put(ZIP, "NO_THREATS_FOUND");
    var members = new LinkedHashMap<String, String>();
    members.put(DATASET + ".csv", header() + "\n");
    members.put(DATASET + "_multires.csv", "a,b\n");
    fake.objects.put(ZIP, zip(members));
    var failOnce = new AtomicBoolean(true);
    doAnswer(invocation -> {
      if (fake.completedKeys.contains(CSV) && failOnce.getAndSet(false)) {
        throw S3Exception.builder().statusCode(503).message("upload failed").build();
      }
      return fake.client().uploadPart(
          invocation.<java.util.function.Consumer<UploadPartRequest.Builder>>getArgument(0),
          invocation.getArgument(1, RequestBody.class));
    }).when(s3).uploadPart(any(java.util.function.Consumer.class), any(RequestBody.class));

    assertThrows(S3Exception.class, () -> service.extract(REQUEST, fullContext));
    String oldVersion = state().path("files").path(ZIP).path("members")
        .path(DATASET + ".csv").path("versionId").asText();
    fake.objects.remove(CSV);

    assertEquals(ExtractionResult.Status.IN_PROGRESS, service.extract(REQUEST, fullContext).status());
    String newVersion = fake.versions.get(CSV);
    assertNotEquals(oldVersion, newVersion);
    assertArrayEquals(members.get(DATASET + ".csv").getBytes(StandardCharsets.UTF_8),
        fake.objects.get(CSV));
    JsonNode primary = state().path("files").path(ZIP).path("members").path(DATASET + ".csv");
    assertEquals("COMPLETE", primary.path("status").asText());
    assertEquals(newVersion, primary.path("versionId").asText());
    assertEquals(newVersion, RuntimeSupport.JSON.readTree(
        fake.objects.get(StreamingS3.checksumKey(CSV))).path("versionId").asText());
    assertEquals(List.of(CSV, CSV, PREFIX + "downloads/" + DATASET + "/"
        + DATASET + "_multires.csv"), fake.completedKeys);
  }

  @Test
  void recoversMissingOutputUsingLegacyChecksumEvidence() throws Exception {
    service = component(List.of(policy(List.of("_multires"), List.of(), 1024 * 1024)));
    fake.scanStatus.put(ZIP, "NO_THREATS_FOUND");
    var members = new LinkedHashMap<String, String>();
    members.put(DATASET + ".csv", header() + "\n");
    members.put(DATASET + "_multires.csv", "a,b\n");
    fake.objects.put(ZIP, zip(members));
    var failOnce = new AtomicBoolean(true);
    doAnswer(invocation -> {
      if (fake.completedKeys.contains(CSV) && failOnce.getAndSet(false)) {
        throw S3Exception.builder().statusCode(503).message("upload failed").build();
      }
      return fake.client().uploadPart(
          invocation.<java.util.function.Consumer<UploadPartRequest.Builder>>getArgument(0),
          invocation.getArgument(1, RequestBody.class));
    }).when(s3).uploadPart(any(java.util.function.Consumer.class), any(RequestBody.class));

    assertThrows(S3Exception.class, () -> service.extract(REQUEST, fullContext));
    var oldState = (com.fasterxml.jackson.databind.node.ObjectNode) state();
    ((com.fasterxml.jackson.databind.node.ObjectNode) oldState.path("files").path(ZIP))
        .remove("members");
    fake.objects.put(STATE, RuntimeSupport.JSON.writeValueAsBytes(oldState));
    fake.objects.remove(CSV);

    assertEquals(ExtractionResult.Status.IN_PROGRESS, service.extract(REQUEST, fullContext).status());
    assertEquals("COMPLETE", state().path("files").path(ZIP).path("members")
        .path(DATASET + ".csv").path("status").asText());
    assertEquals(fake.versions.get(CSV), state().path("files").path(ZIP).path("members")
        .path(DATASET + ".csv").path("versionId").asText());
  }

  @Test
  void doesNotCompleteMissingOutputIfPinnedZipDiffersFromCheckpoint() throws Exception {
    service = component(List.of(policy(List.of("_multires"), List.of(), 1024 * 1024)));
    fake.scanStatus.put(ZIP, "NO_THREATS_FOUND");
    var members = new LinkedHashMap<String, String>();
    members.put(DATASET + ".csv", header() + "\nfirst\n");
    members.put(DATASET + "_multires.csv", "a,b\n");
    fake.objects.put(ZIP, zip(members));
    var failOnce = new AtomicBoolean(true);
    doAnswer(invocation -> {
      if (fake.completedKeys.contains(CSV) && failOnce.getAndSet(false)) {
        throw S3Exception.builder().statusCode(503).message("upload failed").build();
      }
      return fake.client().uploadPart(
          invocation.<java.util.function.Consumer<UploadPartRequest.Builder>>getArgument(0),
          invocation.getArgument(1, RequestBody.class));
    }).when(s3).uploadPart(any(java.util.function.Consumer.class), any(RequestBody.class));

    assertThrows(S3Exception.class, () -> service.extract(REQUEST, fullContext));
    fake.objects.remove(CSV);
    members.put(DATASET + ".csv", header() + "\nother\n");
    fake.objects.put(ZIP, zip(members));

    var failure = assertThrows(IllegalArgumentException.class,
        () -> service.extract(REQUEST, fullContext));
    assertTrue(failure.getMessage().contains("differs from recorded checksum"));
    assertFalse(fake.objects.containsKey(CSV));
    assertEquals("RECOVERING", state().path("files").path(ZIP).path("members")
        .path(DATASET + ".csv").path("status").asText());
    assertEquals("PENDING", state().path("files").path(ZIP).path("status").asText());
  }

  @Test
  void resumesRecoveryAfterOutputUploadButBeforeEvidenceUpdate() throws Exception {
    service = component(List.of(policy(List.of("_multires"), List.of(), 1024 * 1024)));
    fake.scanStatus.put(ZIP, "NO_THREATS_FOUND");
    var members = new LinkedHashMap<String, String>();
    members.put(DATASET + ".csv", header() + "\n");
    members.put(DATASET + "_multires.csv", "a,b\n");
    fake.objects.put(ZIP, zip(members));
    var uploadFails = new AtomicBoolean(true);
    doAnswer(invocation -> {
      if (fake.completedKeys.contains(CSV) && uploadFails.getAndSet(false)) {
        throw S3Exception.builder().statusCode(503).message("upload failed").build();
      }
      return fake.client().uploadPart(
          invocation.<java.util.function.Consumer<UploadPartRequest.Builder>>getArgument(0),
          invocation.getArgument(1, RequestBody.class));
    }).when(s3).uploadPart(any(java.util.function.Consumer.class), any(RequestBody.class));
    assertThrows(S3Exception.class, () -> service.extract(REQUEST, fullContext));
    fake.objects.remove(CSV);

    String evidenceKey = StreamingS3.checksumKey(CSV);
    var evidenceFails = new AtomicBoolean(true);
    doAnswer(invocation -> {
      PutObjectRequest request = invocation.getArgument(0);
      if (evidenceKey.equals(request.key()) && evidenceFails.getAndSet(false)) {
        throw S3Exception.builder().statusCode(503).message("evidence failed").build();
      }
      return fake.client().putObject(request, invocation.getArgument(1, RequestBody.class));
    }).when(s3).putObject(any(PutObjectRequest.class), any(RequestBody.class));

    assertThrows(S3Exception.class, () -> service.extract(REQUEST, fullContext));
    assertTrue(fake.objects.containsKey(CSV));
    assertEquals("RECOVERING", state().path("files").path(ZIP).path("members")
        .path(DATASET + ".csv").path("status").asText());
    assertEquals(ExtractionResult.Status.IN_PROGRESS, service.extract(REQUEST, fullContext).status());
    assertEquals("COMPLETE", state().path("files").path(ZIP).path("members")
        .path(DATASET + ".csv").path("status").asText());
    assertEquals(fake.versions.get(CSV), RuntimeSupport.JSON.readTree(
        fake.objects.get(evidenceKey)).path("versionId").asText());
    assertEquals(2, fake.completedKeys.stream().filter(CSV::equals).count());
  }

  @Test
  void rejectsHeaderMismatchWithoutMarkingFileUnpacked() throws Exception {
    fake.scanStatus.put(ZIP, "NO_THREATS_FOUND");
    fake.objects.put(ZIP, zip(Map.of(DATASET + ".csv", "wrong,header\n")));
    assertRejected("CSV_HEADER_MISMATCH");
    assertEquals("PENDING", state().path("files").path(ZIP).path("status").asText());
    assertFalse(fake.objects.containsKey(CSV));
  }

  @Test
  void rejectsHighlyCompressedZipWithoutMarkingFileUnpacked() throws Exception {
    fake.scanStatus.put(ZIP, "NO_THREATS_FOUND");
    fake.objects.put(ZIP, zip(Map.of(DATASET + ".csv", header() + "\n" + "x".repeat(30_000))));
    assertRejected("INVALID_ZIP");
    assertEquals("PENDING", state().path("files").path(ZIP).path("status").asText());
    assertFalse(state().path("complete").asBoolean());
  }

  @Test
  void rejectsArchiveExceedingTotalCompressionRatio() throws Exception {
    service = component(List.of(policy(DATASET, List.of("_first", "_second"), List.of(),
        1024 * 1024, 99, 1000, 2)));
    fake.scanStatus.put(ZIP, "NO_THREATS_FOUND");
    var entries = new LinkedHashMap<String, String>();
    entries.put(DATASET + "_first.csv", "a,b\n");
    entries.put(DATASET + "_second.csv", "x".repeat(1_000));
    fake.objects.put(ZIP, zip(entries));
    assertRejected("INVALID_ZIP");
    assertEquals("PENDING", state().path("files").path(ZIP).path("status").asText());
  }

  @Test
  void enforcesEntryRatioIndependentlyOfArchiveRatio() throws Exception {
    service = component(List.of(policy(DATASET, List.of(), List.of(), 1024 * 1024,
        99, 2, 1000)));
    fake.scanStatus.put(ZIP, "NO_THREATS_FOUND");
    fake.objects.put(ZIP, zip(Map.of(DATASET + ".csv", header() + "\n" + "x".repeat(1000))));

    assertRejected("INVALID_ZIP");
    assertEquals("PENDING", state().path("files").path(ZIP).path("status").asText());
  }

  @Test
  void allowsCompressionAtConfiguredRatioLimit() throws Exception {
    service = component(List.of(policy(DATASET, List.of(), List.of(), 1024 * 1024,
        1, 1, 1)));
    fake.scanStatus.put(ZIP, "NO_THREATS_FOUND");
    fake.objects.put(ZIP, storedZip(DATASET + ".csv", header() + "\n"));

    assertEquals(ExtractionResult.Status.IN_PROGRESS, service.extract(REQUEST, fullContext).status());
    assertEquals("UNPACKED", state().path("files").path(ZIP).path("status").asText());
  }

  @Test
  void acceptsRatiosAboveFormerFixedLimitsWhenConfigured() throws Exception {
    service = component(List.of(policy(DATASET, List.of(), List.of(), 1024 * 1024,
        1, 1000, 1000)));
    fake.scanStatus.put(ZIP, "NO_THREATS_FOUND");
    fake.objects.put(ZIP, zip(Map.of(DATASET + ".csv", header() + "\n"
        + "x".repeat(30_000))));

    assertEquals(ExtractionResult.Status.IN_PROGRESS, service.extract(REQUEST, fullContext).status());
    assertEquals("UNPACKED", state().path("files").path(ZIP).path("status").asText());
    assertTrue(fake.objects.get(CSV).length > 30_000);
  }

  @ParameterizedTest
  @ValueSource(strings = {"notes.txt", "../bad.csv", "nested/add_isl_royalmailaddress.csv",
      "add_isl_royalmailaddress_notes.json", "dir\\file.csv"})
  void rejectsUnexpectedOrUnsafeZipEntry(String name) throws Exception {
    fake.scanStatus.put(ZIP, "NO_THREATS_FOUND");
    fake.objects.put(ZIP, zip(Map.of(name, "bad\n")));
    assertRejected("ARCHIVE_VALIDATION_FAILED");
    assertEquals("PENDING", state().path("files").path(ZIP).path("status").asText());
    assertFalse(fake.objects.containsKey(CSV));
  }

  @Test
  void rejectsUnexpectedMemberAfterPrimaryCsvWithoutCompletingVersion() throws Exception {
    fake.scanStatus.put(ZIP, "NO_THREATS_FOUND");
    var entries = new LinkedHashMap<String, String>();
    entries.put(DATASET + ".csv", header() + "\n");
    entries.put("notes.txt", "bad");
    fake.objects.put(ZIP, zip(entries));
    assertRejected("ARCHIVE_VALIDATION_FAILED");
    assertEquals("PENDING", state().path("files").path(ZIP).path("status").asText());
    assertFalse(state().path("complete").asBoolean());
  }

  @Test
  void rejectsMoreThanOneHundredEntries() throws Exception {
    service = component(List.of(policy(
        IntStream.range(0, 101).mapToObj(index -> "_part" + index).toList(),
        List.of(), 1024 * 1024)));
    fake.scanStatus.put(ZIP, "NO_THREATS_FOUND");
    var entries = new LinkedHashMap<String, String>();
    for (int i = 0; i < 101; i++) {
      entries.put(DATASET + "_part" + i + ".csv", "a,b\n" + i + "\n");
    }
    fake.objects.put(ZIP, zip(entries));
    assertRejected("ARCHIVE_VALIDATION_FAILED");
    assertEquals("PENDING", state().path("files").path(ZIP).path("status").asText());
  }

  @Test
  void enforcesDatasetEntryCountBelowGlobalFormerLimit() throws Exception {
    service = component(List.of(policy(DATASET, List.of("_extra"), List.of(), 1024 * 1024,
        1, 100, 20)));
    fake.scanStatus.put(ZIP, "NO_THREATS_FOUND");
    var entries = new LinkedHashMap<String, String>();
    entries.put(DATASET + ".csv", header() + "\n");
    entries.put(DATASET + "_extra.csv", "a,b\n");
    fake.objects.put(ZIP, zip(entries));

    assertRejected("ARCHIVE_VALIDATION_FAILED");
    assertEquals("PENDING", state().path("files").path(ZIP).path("status").asText());
  }

  @Test
  void rejectsOversizedStoredEntry() throws Exception {
    fake.scanStatus.put(ZIP, "NO_THREATS_FOUND");
    fake.objects.put(ZIP, storedZip(DATASET + ".csv", header() + "\n"
        + "x".repeat(1024 * 1024 + 1)));
    assertRejected("INVALID_ZIP");
    assertEquals("PENDING", state().path("files").path(ZIP).path("status").asText());
  }

  @Test
  void enforcesDatasetLimitBelowGlobalLimit() throws Exception {
    service = component(List.of(policy(DATASET, 1024)));
    fake.scanStatus.put(ZIP, "NO_THREATS_FOUND");
    fake.objects.put(ZIP, storedZip(DATASET + ".csv", header() + "\n" + "x".repeat(1024)));

    assertRejected("INVALID_ZIP");
    assertEquals("PENDING", state().path("files").path(ZIP).path("status").asText());
    assertFalse(fake.objects.containsKey(CSV));
  }

  @Test
  void acceptsDatasetLimitAboveGlobalLimit() throws Exception {
    service = component(List.of(policy(DATASET, 2L * 1024 * 1024)));
    fake.scanStatus.put(ZIP, "NO_THREATS_FOUND");
    fake.objects.put(ZIP, storedZip(DATASET + ".csv",
        header() + "\n" + "x".repeat(1024 * 1024 + 1)));

    assertEquals(ExtractionResult.Status.IN_PROGRESS, service.extract(REQUEST, fullContext).status());
    assertTrue(fake.objects.get(CSV).length > 1024 * 1024);
    assertEquals("UNPACKED", state().path("files").path(ZIP).path("status").asText());
  }

  @Test
  void rejectsMissingOrDuplicateDatasetPolicies() throws Exception {
    fake.scanStatus.put(ZIP, "NO_THREATS_FOUND");
    service = component(List.of(policy("other_dataset", 1024 * 1024)));
    assertThrows(IllegalArgumentException.class, () -> service.extract(REQUEST, fullContext));
    service = component(List.of(policy(DATASET, 1024 * 1024), policy(DATASET, 1024 * 1024)));
    assertThrows(IllegalArgumentException.class, () -> service.extract(REQUEST, fullContext));
    assertEquals("PENDING", state().path("files").path(ZIP).path("status").asText());
  }

  @Test
  void rejectsNonPositiveDatasetLimit() {
    assertThrows(IllegalArgumentException.class, () -> policy(DATASET, 0));
    assertThrows(IllegalArgumentException.class, () -> policy(DATASET, -1));
    assertThrows(IllegalArgumentException.class,
        () -> policy(DATASET, List.of(), List.of(), 1024, 0, 100, 20));
    assertThrows(IllegalArgumentException.class,
        () -> policy(DATASET, List.of(), List.of(), 1024, 100, 100, 20));
    assertThrows(IllegalArgumentException.class,
        () -> policy(DATASET, List.of(), List.of(), 1024, 99, 0, 20));
    assertThrows(IllegalArgumentException.class,
        () -> policy(DATASET, List.of(), List.of(), 1024, 99, 100, 0));
  }

  @Test
  void rejectsMissingPrimaryCsv() throws Exception {
    service = component(List.of(policy(List.of("_extra"), List.of(), 1024 * 1024)));
    fake.scanStatus.put(ZIP, "NO_THREATS_FOUND");
    fake.objects.put(ZIP, zip(Map.of(DATASET + "_extra.csv", "a,b\n")));
    assertRejected("MISSING_PRIMARY_CSV");
    assertEquals("PENDING", state().path("files").path(ZIP).path("status").asText());
  }

  @Test
  void rejectsMalformedZip() throws Exception {
    fake.scanStatus.put(ZIP, "NO_THREATS_FOUND");
    byte[] malformed = zip(Map.of(DATASET + ".csv", header() + "\n"));
    malformed[8] = 99;
    fake.objects.put(ZIP, malformed);
    assertRejected("INVALID_ZIP");
    assertEquals("PENDING", state().path("files").path(ZIP).path("status").asText());
  }

  @Test
  void refusesUnexpectedVersionOrManifest() {
    var wrongPackage = Map.<String, Object>of("mode", "FULL", "versions",
        Map.of("data_package", "99999", "version_id", "150450"));
    assertThrows(IllegalArgumentException.class,
        () -> service.extract(wrongPackage, fullContext));
    fake.objects.put(MANIFEST, new String(manifest(), StandardCharsets.UTF_8)
        .replace("\"previous\":null", "\"previous\":\"150449\"")
        .getBytes(StandardCharsets.UTF_8));
    assertThrows(IllegalArgumentException.class,
        () -> service.extract(REQUEST, fullContext));
    assertFalse(fake.objects.containsKey(STATE));
  }

  @ParameterizedTest
  @ValueSource(strings = {"unexpected.txt", "manifest.json", "../other.zip",
      "nested/add_isl_royalmailaddress.zip"})
  void rejectsUnexpectedS3Input(String name) {
    String key = PREFIX + "downloads/" + name;
    fake.objects.put(key, "unexpected".getBytes(StandardCharsets.UTF_8));

    var failure = assertThrows(IllegalArgumentException.class,
        () -> service.extract(REQUEST, fullContext));
    assertEquals("Unexpected FULL input: " + key, failure.getMessage());
    assertFalse(fake.objects.containsKey(STATE));
  }

  @Test
  void rejectsVersionWithNoS3InputFiles() {
    fake.objects.remove(ZIP);
    fake.objects.remove(SUMMARY);

    var failure = assertThrows(IllegalArgumentException.class,
        () -> service.extract(REQUEST, fullContext));
    assertEquals("FULL version has no input files", failure.getMessage());
    assertFalse(fake.objects.containsKey(STATE));
  }

  @Test
  void rejectsUnsupportedCouMode() {
    var request = Map.<String, Object>of("mode", "COU");
    var failure = assertThrows(IllegalArgumentException.class,
        () -> service.extract(request, fullContext));
    assertEquals("Unsupported extraction mode", failure.getMessage());
    assertFalse(fake.objects.containsKey(STATE));
  }

  @Test
  void rejectsMissingFullFeedConfiguration() {
    var extractor = new FullVersionExtraction(s3, null, null, service);
    var failure = assertThrows(IllegalArgumentException.class,
        () -> extractor.extract(FullExtractionRequest.parse(REQUEST), fullContext));
    assertEquals("Missing FULL feed configuration", failure.getMessage());
  }

  @Test
  void uploadsAllowedEntriesAndRecordsSkippedEntries() throws Exception {
    service = component(List.of(policy(List.of("_multires"), List.of("_rltenty"), 1024 * 1024)));
    fake.scanStatus.put(ZIP, "NO_THREATS_FOUND");
    fake.scanStatus.put(SUMMARY, "NO_THREATS_FOUND");
    var entries = new LinkedHashMap<String, String>();
    entries.put(DATASET + ".csv", header() + "\n");
    entries.put(DATASET + "_multires.csv", "a,b\n");
    entries.put(DATASET + "_rltenty.csv", "skipped\n");
    fake.objects.put(ZIP, zip(entries));
    assertEquals(ExtractionResult.Status.COMPLETE,
        service.extract(REQUEST, TestContext.withRemainingMillis(900_000)).status());
    String ancillary = PREFIX + "downloads/" + DATASET + "/" + DATASET + "_multires.csv";
    assertArrayEquals("a,b\n".getBytes(StandardCharsets.UTF_8), fake.objects.get(ancillary));
    assertFalse(fake.objects.containsKey(PREFIX + "downloads/" + DATASET + "/"
        + DATASET + "_rltenty.csv"));
    assertEquals(2, state().path("files").path(ZIP).path("outputKeys").size());
    assertEquals(DATASET + "_rltenty.csv",
        state().path("files").path(ZIP).path("skipped").get(0).asText());
    assertEquals(0, state().path("files").path(SUMMARY).path("skipped").size());
  }

  @ParameterizedTest
  @ValueSource(booleans = {false, true})
  void seeksPastSkippedMemberToExtractLaterEntryWithoutReadingItsPayload(boolean compressed)
      throws Exception {
    service = component(List.of(policy(List.of("_otrclass"), List.of("_rltenty"), 1024 * 1024)));
    fake.scanStatus.put(ZIP, "NO_THREATS_FOUND");
    fake.scanStatus.put(SUMMARY, "NO_THREATS_FOUND");
    String skippedName = DATASET + "_rltenty.csv";
    String skippedContent = compressed
        ? IntStream.range(0, 20_000).mapToObj(Integer::toString)
            .collect(java.util.stream.Collectors.joining(","))
        : "x".repeat(128 * 1024);
    var entries = new LinkedHashMap<String, String>();
    entries.put(DATASET + ".csv", header() + "\n");
    entries.put(skippedName, skippedContent);
    entries.put(DATASET + "_otrclass.csv", "code\n");
    byte[] archive = compressed ? zip(entries) : storedZip(entries);
    fake.objects.put(ZIP, archive);

    assertEquals(ExtractionResult.Status.COMPLETE, service.extract(REQUEST, fullContext).status());
    assertArrayEquals("code\n".getBytes(StandardCharsets.UTF_8),
        fake.objects.get(PREFIX + "downloads/" + DATASET + "/" + DATASET + "_otrclass.csv"));
    assertEquals(skippedContent.length(), state().path("files").path(ZIP).path("members")
        .path(skippedName).path("bytes").asLong());

    int nameAt = indexOf(archive, skippedName.getBytes(StandardCharsets.UTF_8));
    assertTrue(nameAt >= 30);
    int extraLength = Short.toUnsignedInt(ByteBuffer.wrap(archive)
        .order(ByteOrder.LITTLE_ENDIAN).getShort(nameAt - 2));
    long skippedStart = nameAt + skippedName.length() + extraLength;
    long skippedEnd = skippedStart
        + (compressed ? compressedSizeFromCentralDirectory(archive, skippedName)
            : skippedContent.length()) - 1;
    var requests = org.mockito.ArgumentCaptor.forClass(GetObjectRequest.class);
    verify(s3, atLeastOnce()).getObject(requests.capture());
    for (GetObjectRequest request : requests.getAllValues()) {
      if (!ZIP.equals(request.key())) continue;
      assertEquals("zip-v1", request.versionId());
      assertNotNull(request.range());
      String[] bounds = request.range().substring("bytes=".length()).split("-");
      long start = Long.parseLong(bounds[0]);
      long end = Long.parseLong(bounds[1]);
      assertTrue(end < skippedStart || start > skippedEnd,
          "Range overlaps skipped ZIP member payload: " + request.range());
    }
  }

  @Test
  void extractsZip64MembersUsingVersionPinnedRanges() throws Exception {
    fake.scanStatus.put(ZIP, "NO_THREATS_FOUND");
    fake.scanStatus.put(SUMMARY, "NO_THREATS_FOUND");
    var bytes = new ByteArrayOutputStream();
    try (var zip = new ZipArchiveOutputStream(bytes)) {
      zip.setUseZip64(Zip64Mode.Always);
      zip.putArchiveEntry(new ZipArchiveEntry(DATASET + ".csv"));
      zip.write((header() + "\n").getBytes(StandardCharsets.UTF_8));
      zip.closeArchiveEntry();
    }
    fake.objects.put(ZIP, bytes.toByteArray());

    assertEquals(ExtractionResult.Status.COMPLETE, service.extract(REQUEST, fullContext).status());
    assertArrayEquals((header() + "\n").getBytes(StandardCharsets.UTF_8), fake.objects.get(CSV));
  }

  @Test
  void rejectsCorruptExtractedMemberCrc() throws Exception {
    fake.scanStatus.put(ZIP, "NO_THREATS_FOUND");
    byte[] archive = storedZip(DATASET + ".csv", header() + "\nbody\n");
    int nameAt = indexOf(archive, (DATASET + ".csv").getBytes(StandardCharsets.UTF_8));
    archive[nameAt + (DATASET + ".csv").length() + header().length() + 1] ^= 1;
    fake.objects.put(ZIP, archive);

    assertRejected("INVALID_ZIP");
    assertFalse(fake.objects.containsKey(CSV));
  }

  @Test
  void rejectsTruncatedZipIndex() throws Exception {
    fake.scanStatus.put(ZIP, "NO_THREATS_FOUND");
    byte[] archive = fake.objects.get(ZIP);
    fake.objects.put(ZIP, Arrays.copyOf(archive, archive.length - 30));

    assertRejected("INVALID_ZIP");
    assertFalse(fake.objects.containsKey(CSV));
  }

  @Test
  void rejectsEmptyZip() throws Exception {
    fake.scanStatus.put(ZIP, "NO_THREATS_FOUND");
    fake.objects.put(ZIP, new byte[0]);

    assertRejected("INVALID_ZIP");
    assertFalse(fake.objects.containsKey(CSV));
  }

  @Test
  void rejectsUnlistedAncillaryEntryAfterPrimaryCsv() throws Exception {
    fake.scanStatus.put(ZIP, "NO_THREATS_FOUND");
    var entries = new LinkedHashMap<String, String>();
    entries.put(DATASET + ".csv", header() + "\n");
    entries.put(DATASET + "_extra.csv", "a,b\n");
    fake.objects.put(ZIP, zip(entries));

    assertRejected("ARCHIVE_VALIDATION_FAILED");
    assertEquals("PENDING", state().path("files").path(ZIP).path("status").asText());
    assertFalse(state().path("files").path(ZIP).has("skipped"));
  }

  @Test
  void skippedEntryStillCountsAgainstExpandedSizeLimit() throws Exception {
    service = component(List.of(policy(List.of(), List.of("_rltenty"), 1024)));
    fake.scanStatus.put(ZIP, "NO_THREATS_FOUND");
    fake.objects.put(ZIP, storedZip(DATASET + "_rltenty.csv", "x".repeat(1025)));

    assertRejected("INVALID_ZIP");
    assertEquals("PENDING", state().path("files").path(ZIP).path("status").asText());
    assertFalse(state().path("files").path(ZIP).has("skipped"));
  }

  @Test
  void skippedEntryStillCountsAgainstCompressionRatioLimit() throws Exception {
    service = component(List.of(policy(List.of(), List.of("_rltenty"), 1024 * 1024)));
    fake.scanStatus.put(ZIP, "NO_THREATS_FOUND");
    fake.objects.put(ZIP, zip(Map.of(DATASET + "_rltenty.csv", "x".repeat(30_000))));

    assertRejected("INVALID_ZIP");
    assertEquals("PENDING", state().path("files").path(ZIP).path("status").asText());
    assertFalse(state().path("files").path(ZIP).has("skipped"));
  }

  @Test
  void rejectsInvalidEntryPolicies() {
    assertThrows(IllegalArgumentException.class,
        () -> policy(List.of("_multires", "_multires"), List.of(), 1024));
    assertThrows(IllegalArgumentException.class,
        () -> policy(List.of("_multires"), List.of("_multires"), 1024));
    assertThrows(IllegalArgumentException.class,
        () -> policy(List.of("_multires"), List.of("_rltenty", "_rltenty"), 1024));
    assertThrows(IllegalArgumentException.class,
        () -> policy(List.of("multires"), List.of(), 1024));
    assertThrows(IllegalArgumentException.class,
        () -> new ExtractionProperties.DatasetPolicy(DATASET, null, List.of(),
            1024, 99, 100, 20));
  }

  private JsonNode state() throws Exception {
    return RuntimeSupport.JSON.readTree(fake.objects.get(STATE));
  }

  private void assertRejected(String reasonCode) throws Exception {
    var attention = service.extract(REQUEST, fullContext);
    assertEquals(ExtractionResult.Status.NEEDS_ATTENTION, attention.status());
    assertEquals("FULL", attention.mode());
    assertEquals("150450", attention.version());
    assertEquals(0, attention.filesDone());
    assertEquals(2, attention.filesRemaining());
    assertEquals(List.of(new ExtractionResult.Failure(ZIP, reasonCode)), attention.failed());
    JsonNode response = RuntimeSupport.JSON.valueToTree(attention);
    assertEquals("NEEDS_ATTENTION", response.path("status").asText());
    assertEquals(ZIP, response.path("failed").get(0).path("key").asText());
    assertEquals(reasonCode, response.path("failed").get(0).path("code").asText());
  }

  private static String header() {
    return DatasetCatalog.tables().stream()
        .filter(table -> DATASET.equals(table.name())).findFirst().orElseThrow().header();
  }

  private byte[] manifest() {
    return """
        {"mode":"FULL","packageId":"24833","versionId":"150450","previous":null}
        """.getBytes(StandardCharsets.UTF_8);
  }

  private static byte[] zip(Map<String, String> members) throws Exception {
    var bytes = new ByteArrayOutputStream();
    try (var zip = new ZipOutputStream(bytes)) {
      for (var entry : members.entrySet()) {
        zip.putNextEntry(new ZipEntry(entry.getKey()));
        zip.write(entry.getValue().getBytes(StandardCharsets.UTF_8));
        zip.closeEntry();
      }
    }
    return bytes.toByteArray();
  }

  private static byte[] storedZip(String name, String value) throws Exception {
    byte[] content = value.getBytes(StandardCharsets.UTF_8);
    var crc = new CRC32();
    crc.update(content);
    var bytes = new ByteArrayOutputStream();
    try (var zip = new ZipOutputStream(bytes)) {
      var entry = new ZipEntry(name);
      entry.setMethod(ZipEntry.STORED);
      entry.setSize(content.length);
      entry.setCompressedSize(content.length);
      entry.setCrc(crc.getValue());
      zip.putNextEntry(entry);
      zip.write(content);
      zip.closeEntry();
    }
    return bytes.toByteArray();
  }

  private static byte[] storedZip(Map<String, String> members) throws Exception {
    var bytes = new ByteArrayOutputStream();
    try (var zip = new ZipOutputStream(bytes)) {
      for (var member : members.entrySet()) {
        byte[] content = member.getValue().getBytes(StandardCharsets.UTF_8);
        var crc = new CRC32();
        crc.update(content);
        var entry = new ZipEntry(member.getKey());
        entry.setMethod(ZipEntry.STORED);
        entry.setSize(content.length);
        entry.setCompressedSize(content.length);
        entry.setCrc(crc.getValue());
        zip.putNextEntry(entry);
        zip.write(content);
        zip.closeEntry();
      }
    }
    return bytes.toByteArray();
  }

  private static int indexOf(byte[] data, byte[] sequence) {
    for (int i = 0; i <= data.length - sequence.length; i++) {
      if (Arrays.equals(data, i, i + sequence.length, sequence, 0, sequence.length)) return i;
    }
    return -1;
  }

  private static long compressedSizeFromCentralDirectory(byte[] archive, String name) {
    byte[] nameBytes = name.getBytes(StandardCharsets.UTF_8);
    int nameAt = indexOf(archive, nameBytes);
    var fields = ByteBuffer.wrap(archive).order(ByteOrder.LITTLE_ENDIAN);
    for (int i = nameAt + nameBytes.length; i < archive.length - 46; i++) {
      if (fields.getInt(i) == 0x02014b50
          && i + 46 + nameBytes.length <= archive.length
          && Arrays.equals(archive, i + 46, i + 46 + nameBytes.length,
              nameBytes, 0, nameBytes.length)) {
        return Integer.toUnsignedLong(fields.getInt(i + 20));
      }
    }
    throw new IllegalArgumentException("Skipped member missing from ZIP index");
  }
}
