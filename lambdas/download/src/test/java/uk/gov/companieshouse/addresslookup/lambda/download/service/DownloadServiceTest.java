package uk.gov.companieshouse.addresslookup.lambda.download.service;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;
import static uk.gov.companieshouse.addresslookup.lambda.shared.runtime.RuntimeSupport.JSON;

import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.LoggerContext;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;
import com.amazonaws.services.lambda.runtime.Context;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import java.io.ByteArrayInputStream;
import java.lang.reflect.Proxy;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.Duration;
import java.util.Arrays;
import java.util.HashMap;
import java.util.HexFormat;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.slf4j.LoggerFactory;
import uk.gov.companieshouse.addresslookup.lambda.shared.acquisition.AcquisitionProperties;
import uk.gov.companieshouse.addresslookup.lambda.shared.os.OsClient;
import uk.gov.companieshouse.addresslookup.lambda.shared.testsupport.FakeS3;
import uk.gov.companieshouse.addresslookup.lambda.shared.util.KeyLayout;
import uk.gov.companieshouse.logging.StructuredLogger;
import uk.gov.companieshouse.release.model.Dataset;

@ExtendWith(MockitoExtension.class)
final class DownloadServiceTest {
  private static final Logger STRUCTURED_LOGGER =
      (Logger) LoggerFactory.getLogger(StructuredLogger.class);
  private static final String BUCKET = "landing-bucket";
  private static final String PLAN_KEY =
      "initial_full/12345/159159/manifest/data_package_manifest.json";
  private static final String RELEASE_ID = "4b845f1a-b0dc-33f1-b76b-b76bb7cd0c56";
  private static final String DATASET = "add_gb_royalmailaddress";
  @Mock private OsClient os;

  @Test
  void downloadsOnlyTheDatasetNamedByTheEventBridgeCommand() throws Exception {
    byte[] zip = "local test zip bytes".getBytes(StandardCharsets.UTF_8);
    JsonNode manifest = manifestWithSelectedMd5(md5(zip));
    var s3 = new FakeS3();
    s3.objects.put(PLAN_KEY, JSON.writeValueAsBytes(manifest));
    String expectedUrl = manifest.path(DATASET).path("zip").path("url").asText();
    when(os.download(expectedUrl)).thenReturn(new ByteArrayInputStream(zip));
    var service = new DownloadService(s3.client(), os, properties());

    assertEquals("ZIP_SCAN", service.download(event(), context()));

    String expectedZipKey = "initial_full/12345/159159/downloads/" + DATASET + ".zip";
    verify(os).download(expectedUrl);
    assertEquals(List.of(expectedZipKey), s3.completedKeys);
    var extractionInput = KeyLayout.parseLandingObject(s3.completedKeys.getFirst());
    assertEquals(KeyLayout.Feed.INITIAL_FULL, extractionInput.packageVersion().feed());
    assertEquals("12345", extractionInput.packageVersion().packageId());
    assertEquals("159159", extractionInput.packageVersion().versionId());
    assertEquals(Dataset.named(DATASET), extractionInput.dataset());
    assertEquals(KeyLayout.LandingFileType.ZIP, extractionInput.fileType());
    assertTrue(s3.completedKeys.stream().noneMatch(k -> k.contains("add_gb_builtaddress")));
    assertTrue(s3.objects.keySet().stream().noneMatch(k -> k.endsWith(".receipt.json")));
    String checksumKey =
        "metadata/checksums/" + sha256(expectedZipKey.getBytes(StandardCharsets.UTF_8)) + ".json";
    JsonNode evidence = JSON.readTree(s3.objects.get(checksumKey));
    assertEquals(BUCKET, evidence.path("bucket").asText());
    assertEquals(expectedZipKey, evidence.path("key").asText());
    assertEquals(md5(zip), evidence.path("md5").asText());
    assertEquals(sha256(zip), evidence.path("sha256").asText());
    assertEquals(zip.length, evidence.path("bytes").asLong());
    assertFalse(evidence.path("versionId").asText().isBlank());
    assertTrue(s3.objects.keySet().stream().noneMatch(k -> k.endsWith(".download-attempt.json")));
  }

  @Test
  void rejectsNullEvent() {
    assertRejected(null, "Missing download event");
  }

  @Test
  void rejectsUnexpectedSource() throws Exception {
    var event = event();
    event.put("source", "other.source");

    assertRejected(event, "Unexpected download event");
  }

  @Test
  void rejectsUnexpectedDetailType() throws Exception {
    var event = event();
    event.put("detail-type", "SCAN");

    assertRejected(event, "Unexpected download event");
  }

  @Test
  void rejectsCommandWithoutDetail() throws Exception {
    var event = event();
    event.remove("detail");

    assertRejected(event, "Missing detail in download command");
  }

  @Test
  void rejectsCommandWithNullDetail() throws Exception {
    var event = event();
    event.put("detail", null);

    assertRejected(event, "Missing detail in download command");
  }

  @Test
  void rejectsCommandWithEmptyDetail() throws Exception {
    var event = event();
    event.put("detail", new HashMap<String, Object>());

    assertRejected(event, "Missing detail in download command");
  }

  @Test
  void rejectsCommandWithNonObjectDetail() throws Exception {
    var event = event();
    event.put("detail", "not-an-object");

    assertRejected(event, "Missing detail in download command");
  }

  @Test
  void rejectsCommandWithoutPlanKey() throws Exception {
    var event = event();
    event.put("detail", Map.of("dataset", DATASET));

    assertRejected(event, "Missing detail.planKey in download command");
  }

  @Test
  void rejectsCommandWithNullBlankOrNonTextPlanKey() throws Exception {
    for (Object planKey : Arrays.asList(null, "", "   ", 123, true, List.of(PLAN_KEY))) {
      var detail = new HashMap<String, Object>();
      detail.put("planKey", planKey);
      detail.put("dataset", DATASET);
      var event = event();
      event.put("detail", detail);

      assertRejected(event, "Missing detail.planKey in download command");
    }
  }

  @Test
  void rejectsCommandWithInvalidPlanKeyFormatBeforeReadingS3() throws Exception {
    var event = event();
    event.put(
        "detail", Map.of("planKey", "acquisitions/not-a-date/not-a-uuid.json", "dataset", DATASET));

    assertRejected(event, "Invalid plan key");
  }

  @Test
  void rejectsCommandWithoutDatasetBeforeReadingS3() throws Exception {
    var event = event();
    event.put("detail", Map.of("planKey", PLAN_KEY));

    assertRejected(event, "Missing detail.dataset in download command");
  }

  @Test
  void rejectsCommandWithNullBlankOrNonTextDataset() throws Exception {
    for (Object dataset : Arrays.asList(null, "", "   ", 123, true, List.of(DATASET))) {
      var detail = new HashMap<String, Object>();
      detail.put("planKey", PLAN_KEY);
      detail.put("dataset", dataset);
      var event = event();
      event.put("detail", detail);

      assertRejected(event, "Missing detail.dataset in download command");
    }
  }

  @Test
  void rejectsCommandWithUnknownDataset() throws Exception {
    var event = event();
    event.put("detail", Map.of("planKey", PLAN_KEY, "dataset", "not_a_real_dataset"));
    var s3 = new FakeS3();
    var service = new DownloadService(s3.client(), os, properties());
    var context = context();

    assertThrows(Exception.class, () -> service.download(event, context));

    assertTrue(s3.objects.isEmpty());
    verifyNoInteractions(os);
  }

  @Test
  void trimsPlanKeyAndDatasetBeforeProcessing() throws Exception {
    byte[] zip = "trimmed command zip".getBytes(StandardCharsets.UTF_8);
    var s3 = new FakeS3();
    s3.objects.put(PLAN_KEY, JSON.writeValueAsBytes(manifestWithSelectedMd5(md5(zip))));
    when(os.download(anyString())).thenReturn(new ByteArrayInputStream(zip));
    var event = event();
    event.put(
        "detail", Map.of("planKey", "  " + PLAN_KEY + "  ", "dataset", "  " + DATASET + "  "));
    var service = new DownloadService(s3.client(), os, properties());

    assertEquals("ZIP_SCAN", service.download(event, context()));
    assertEquals(
        List.of("initial_full/12345/159159/downloads/" + DATASET + ".zip"), s3.completedKeys);
  }

  @Test
  void doesNotPublishDownloadCompleteEventAfterSuccessfulDownload() throws Exception {
    byte[] zip = "event zip bytes".getBytes(StandardCharsets.UTF_8);
    var s3 = new FakeS3();
    s3.objects.put(PLAN_KEY, JSON.writeValueAsBytes(manifestWithSelectedMd5(md5(zip))));
    when(os.download(anyString())).thenReturn(new ByteArrayInputStream(zip));
    var service = new DownloadService(s3.client(), os, properties());

    assertEquals("ZIP_SCAN", service.download(event(), context()));

    assertEquals(1, s3.completedKeys.size());
  }

  @Test
  void doesNotPublishEventOnChecksumMismatch() throws Exception {
    var s3 = new FakeS3();
    s3.objects.put(PLAN_KEY, fixtureBytes("4b845f1a-b0dc-33f1-b76b-b76bb7cd0c56.json"));
    when(os.download(anyString()))
        .thenReturn(new ByteArrayInputStream("wrong content".getBytes(StandardCharsets.UTF_8)));
    var service = new DownloadService(s3.client(), os, properties());

    assertEquals("CHECKSUM_MISMATCH", service.download(event(), context()));
  }

  @Test
  void failsWithoutDownloadingWhenPlanFileDoesNotExist() throws Exception {
    var s3 = new FakeS3();
    var service = new DownloadService(s3.client(), os, properties());
    var event = event();
    var context = context();
    String expected = "Acquisition plan not found planKey=" + PLAN_KEY + " dataset=" + DATASET;
    var appender = captureStructuredLogs();
    try {
      var e = assertThrows(IllegalArgumentException.class, () -> service.download(event, context));
      assertEquals(expected, e.getMessage());
      verifyNoInteractions(os);
    } finally {
      stopCapturingStructuredLogs(appender);
    }
    assertTrue(
        appender.list.stream()
            .map(ILoggingEvent::getFormattedMessage)
            .anyMatch(message -> message.contains("Acquisition plan not found")));
  }

  private void assertRejected(Map<String, Object> event, String expectedMessage) {
    var s3 = new FakeS3();
    var service = new DownloadService(s3.client(), os, properties());
    var context = context();

    var failure =
        assertThrows(IllegalArgumentException.class, () -> service.download(event, context));

    assertEquals(expectedMessage, failure.getMessage());
    assertTrue(s3.objects.isEmpty());
    assertTrue(s3.uploads.isEmpty());
    verifyNoInteractions(os);
  }

  @Test
  void eachDatasetCommandDownloadsOnlyItsNamedZip() throws Exception {
    for (String dataset : List.of("add_gb_royalmailaddress", "add_isl_royalmailaddress")) {
      byte[] zip = ("zip for " + dataset).getBytes(StandardCharsets.UTF_8);
      var manifest = manifestWithMd5(dataset, md5(zip));
      var s3 = new FakeS3();
      s3.objects.put(PLAN_KEY, JSON.writeValueAsBytes(manifest));
      var event = event();
      event.put("detail", Map.of("planKey", PLAN_KEY, "dataset", dataset));
      when(os.download(anyString())).thenReturn(new ByteArrayInputStream(zip));
      var service = new DownloadService(s3.client(), os, properties());

      assertEquals("ZIP_SCAN", service.download(event, context()));
      assertEquals(
          List.of("initial_full/12345/159159/downloads/" + dataset + ".zip"), s3.completedKeys);
      assertTrue(s3.objects.keySet().stream().noneMatch(k -> k.endsWith(".receipt.json")));
      assertTrue(s3.objects.keySet().stream().noneMatch(k -> k.endsWith(".download-attempt.json")));
    }
  }

  @Test
  void checksumMismatchAbortsUploadAndDoesNotPublishReceipt() throws Exception {
    var s3 = new FakeS3();
    s3.objects.put(PLAN_KEY, fixtureBytes("4b845f1a-b0dc-33f1-b76b-b76bb7cd0c56.json"));
    when(os.download(anyString()))
        .thenReturn(new ByteArrayInputStream("wrong content".getBytes(StandardCharsets.UTF_8)));
    var service = new DownloadService(s3.client(), os, properties());
    var appender = captureStructuredLogs();
    try {
      assertEquals("CHECKSUM_MISMATCH", service.download(event(), context()));
    } finally {
      stopCapturingStructuredLogs(appender);
    }
    assertTrue(
        appender.list.stream()
            .filter(event -> event.getFormattedMessage().equals("Download checksum mismatch"))
            .map(event -> structuredField(event, "checksum_mismatch"))
            .anyMatch(mismatch ->
                mismatch != null && mismatch.contains("expected=") && mismatch.contains("actual=")),
        "mismatch must be logged with both MD5 values");
    assertTrue(s3.abortedUploads > 0, "multipart upload should be aborted");
    assertTrue(s3.objects.keySet().stream().noneMatch(k -> k.endsWith(".receipt.json")));
    assertTrue(s3.completedKeys.isEmpty(), "checksum mismatch must not complete a ZIP object");
    assertTrue(s3.objects.keySet().stream().noneMatch(k -> k.endsWith(".download-attempt.json")));
  }

  private static ListAppender<ILoggingEvent> captureStructuredLogs() {
    var appender = new ListAppender<ILoggingEvent>();
    appender.setContext((LoggerContext) LoggerFactory.getILoggerFactory());
    appender.start();
    STRUCTURED_LOGGER.addAppender(appender);
    return appender;
  }

  private static void stopCapturingStructuredLogs(ListAppender<ILoggingEvent> appender) {
    STRUCTURED_LOGGER.detachAppender(appender);
    appender.stop();
  }

  private static String structuredField(ILoggingEvent event, String field) {
    for (Object argument : event.getArgumentArray()) {
      if (argument instanceof Map<?, ?> logData
          && logData.get("data") instanceof Map<?, ?> data
          && data.get(field) != null) {
        return data.get(field).toString();
      }
    }
    return null;
  }

  @Test
  void repeatedDownloadSkipsCurrentVersionWithMatchingEvidence() throws Exception {
    var s3 = new FakeS3();
    byte[] zip = "repeat download".getBytes(StandardCharsets.UTF_8);
    String zipKey = "initial_full/12345/159159/downloads/" + DATASET + ".zip";
    s3.objects.put(PLAN_KEY, JSON.writeValueAsBytes(manifestWithSelectedMd5(md5(zip))));
    s3.objects.put(zipKey, "old content".getBytes(StandardCharsets.UTF_8));
    when(os.download(anyString())).thenReturn(new ByteArrayInputStream(zip));
    var service = new DownloadService(s3.client(), os, properties());

    assertEquals("ZIP_SCAN", service.download(event(), context()));
    assertEquals("SKIPPED", service.download(event(), context()));
    assertEquals(List.of(zipKey), s3.completedKeys);
    verify(os).download(anyString());
    assertArrayEquals(zip, s3.objects.get(zipKey));
    assertTrue(s3.objects.keySet().stream().noneMatch(k -> k.endsWith(".receipt.json")));
  }

  @org.junit.jupiter.params.ParameterizedTest
  @org.junit.jupiter.params.provider.ValueSource(strings = {"missing", "replaced", "md5"})
  void downloadsAgainWhenExistingZipEvidenceIsStale(String reason) throws Exception {
    var s3 = new FakeS3();
    byte[] zip = "repeat download".getBytes(StandardCharsets.UTF_8);
    String zipKey = "initial_full/12345/159159/downloads/" + DATASET + ".zip";
    s3.objects.put(PLAN_KEY, JSON.writeValueAsBytes(manifestWithSelectedMd5(md5(zip))));
    when(os.download(anyString()))
        .thenAnswer(call -> new ByteArrayInputStream(zip));
    var service = new DownloadService(s3.client(), os, properties());
    assertEquals("ZIP_SCAN", service.download(event(), context()));
    switch (reason) {
      case "missing" -> s3.objects.remove(zipKey);
      case "replaced" -> s3.versions.put(zipKey, "replacement-version");
      case "md5" -> {
        String evidenceKey = KeyLayout.checksumKey(zipKey);
        var evidence = (ObjectNode) JSON.readTree(s3.objects.get(evidenceKey));
        evidence.put("md5", "0".repeat(32));
        s3.objects.put(evidenceKey, JSON.writeValueAsBytes(evidence));
      }
      default -> throw new IllegalArgumentException(reason);
    }

    assertEquals("ZIP_SCAN", service.download(event(), context()));
    assertEquals(List.of(zipKey, zipKey), s3.completedKeys);
    assertArrayEquals(zip, s3.objects.get(zipKey));
  }

  private static AcquisitionProperties properties() {
    return new AcquisitionProperties(
        "control-bucket",
        BUCKET,
        "event-bus",
        "redacted",
        "{}",
        512L * 1024L * 1024L,
        null,
        null,
        1024L * 1024L * 1024L,
        20,
        Duration.ofSeconds(20),
        Duration.ofSeconds(840));
  }

  private static Map<String, Object> event() throws Exception {
    return JSON.readValue(fixtureBytes("mock-eventbridge-example.json"), new TypeReference<>() {});
  }

  private static JsonNode manifestWithSelectedMd5(String md5) throws Exception {
    return manifestWithMd5(DATASET, md5);
  }

  private static JsonNode manifestWithMd5(String dataset, String md5) throws Exception {
    ObjectNode manifest =
        (ObjectNode) JSON.readTree(fixtureBytes("4b845f1a-b0dc-33f1-b76b-b76bb7cd0c56.json"));
    ((ObjectNode) manifest.path(dataset).path("zip")).put("md5", md5);
    return manifest;
  }

  private static byte[] fixtureBytes(String name) throws Exception {
    try (var in =
        DownloadServiceTest.class.getResourceAsStream("/example-download-lambda/" + name)) {
      assertNotNull(in, "Missing fixture " + name);
      return in.readAllBytes();
    }
  }

  private static Context context() {
    return (Context)
        Proxy.newProxyInstance(
            Context.class.getClassLoader(),
            new Class<?>[] {Context.class},
            (proxy, method, args) ->
                switch (method.getName()) {
                  case "getRemainingTimeInMillis" -> 900_000;
                  case "getMemoryLimitInMB" -> 1024;
                  default -> null;
                });
  }

  private static String md5(byte[] bytes) throws Exception {
    return HexFormat.of().formatHex(MessageDigest.getInstance("MD5").digest(bytes));
  }

  private static String sha256(byte[] bytes) throws Exception {
    return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(bytes));
  }
}
