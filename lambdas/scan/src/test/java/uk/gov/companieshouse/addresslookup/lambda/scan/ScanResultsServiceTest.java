package uk.gov.companieshouse.addresslookup.lambda.scan;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.JsonNode;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;
import org.junit.jupiter.params.provider.ValueSource;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import software.amazon.awssdk.services.eventbridge.EventBridgeClient;
import software.amazon.awssdk.services.eventbridge.model.PutEventsRequest;
import software.amazon.awssdk.services.eventbridge.model.PutEventsResponse;
import uk.gov.companieshouse.addresslookup.lambda.shared.acquisition.Commands;
import uk.gov.companieshouse.addresslookup.lambda.shared.acquisition.EventPublicationException;

import java.io.InputStream;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.function.Consumer;
import java.util.stream.Stream;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.when;
import static uk.gov.companieshouse.addresslookup.lambda.shared.runtime.RuntimeSupport.JSON;

@ExtendWith(MockitoExtension.class)
class ScanResultsServiceTest {
  private static final String MANIFEST_RELEASE = "f3d8c9a7-7b4e-4f62-a9d1-8c5e72f1b6a4";
  private static final String MANIFEST_KEY =
      "acquisitions/2026-10-15/" + MANIFEST_RELEASE + ".json";
  private static final String RECEIPT_RELEASE = "f3d8c9a7-7b4e-4f62-a9d1-8c5e72f1b6a4";
  private static final String RECEIPT_KEY =
      "Scanned/" + RECEIPT_RELEASE + "/add_gb_royalmailaddress.receipt.json";
  private static final String BUCKET = "ingest-bucket";
  private static final String CLEAN_BUCKET = "clean-bucket";
  private static final String KEY = "zips/20780-131061/add_gb_royalmailaddress.zip";
  private static final String CSV_KEY = "csv/20780-131061/add_gb_royalmailaddress.csv";
  private static final String VERSION = "3HL4kqtJlcpXroDTDmJ.rmSpXd3dIbrHY";
  private static final String INGEST_SOURCE = "uk.gov.companieshouse.address-lookup.ingest";

  private final List<PutEventsRequest> sent = new ArrayList<>();
  private boolean failPublish;
  private boolean rejectEntries;
  private ScanResultsService service;
  @Mock private EventBridgeClient client;

  @BeforeEach
  void setUp() {
    when(client.putEvents(org.mockito.ArgumentMatchers.<Consumer<PutEventsRequest.Builder>>any()))
        .thenAnswer(invocation -> {
          if (failPublish) throw new IllegalStateException("EventBridge unavailable");
          var builder = PutEventsRequest.builder();
          Consumer<PutEventsRequest.Builder> request = invocation.getArgument(0);
          request.accept(builder);
          sent.add(builder.build());
          return PutEventsResponse.builder().failedEntryCount(rejectEntries ? 1 : 0).build();
        });
    service =
        new ScanResultsService(new Commands(client, "bus", JSON), BUCKET, CLEAN_BUCKET);
  }

  @Test
  void cleanManifestPublishesOnlyManifestReadyWithExactObjectAndNoDataset() throws Exception {
    assertEquals("ManifestReady",
        service.scan(event(BUCKET, MANIFEST_KEY, VERSION, "NO_THREATS_FOUND")));
    assertEquals(List.of("ManifestReady"), types());
    assertEquals(INGEST_SOURCE, sent.getFirst().entries().getFirst().source());
    assertEquals(JSON.valueToTree(Map.of(
        "schemaVersion", 1,
        "releaseId", MANIFEST_RELEASE,
        "object", Map.of("bucket", BUCKET, "key", MANIFEST_KEY, "versionId", VERSION))),
        detail(0));
    assertTrue(detail(0).path("schemaVersion").isInt());
    assertFalse(detail(0).has("dataset"));
  }

  @Test
  void infectedManifestPublishesOnlyRejectionWithThreatNames() throws Exception {
    service.scan(eventWithThreats(BUCKET, MANIFEST_KEY, VERSION));
    assertEquals(List.of("StepRejected"), types());
    assertEquals("MALWARE_FOUND", detail(0).path("code").asText());
    assertEquals("manifest-scan", detail(0).path("step").asText());
    assertEquals("Example.Malware", detail(0).path("message").asText());
    assertEquals(MANIFEST_RELEASE, detail(0).path("releaseId").asText());
    assertEquals(VERSION, detail(0).path("object").path("versionId").asText());
    assertFalse(detail(0).has("dataset"));
  }

  @ParameterizedTest(name = "manifest result: [{0}]")
  @ValueSource(strings = {"FAILED", "UNSUPPORTED", "ACCESS_DENIED", "UNKNOWN", ""})
  void inconclusiveManifestsPublishOnlyRejections(String result) throws Exception {
    service.scan(event(BUCKET, MANIFEST_KEY, VERSION, result));
    assertEquals(List.of("StepRejected"), types());
    assertEquals("SCAN_INCONCLUSIVE", detail(0).path("code").asText());
    assertEquals("manifest-scan", detail(0).path("step").asText());
    assertEquals(result.isEmpty() ? "Missing scan result" : result,
        detail(0).path("message").asText());
    assertFalse(detail(0).has("dataset"));
  }

  @ParameterizedTest(name = "{0}")
  @MethodSource("invalidManifestEvents")
  void manifestsRequireIngestBucketVersionAndValidKey(
      String scenario, Map<String, Object> input) throws Exception {
    service.scan(input);
    assertEquals(List.of("StepRejected"), types());
    assertEquals("INVALID_INPUT", detail(0).path("code").asText());
  }

  @Test
  void manifestDuplicatesAreRepublished() throws Exception {
    var input = event(BUCKET, MANIFEST_KEY, VERSION, "NO_THREATS_FOUND");
    service.scan(input);
    service.scan(input);
    assertEquals(List.of("ManifestReady", "ManifestReady"), types());
    assertEquals(detail(0), detail(1));
  }

  @Test
  void manifestRejectedPublicationPropagates() {
    var input = event(BUCKET, MANIFEST_KEY, VERSION, "NO_THREATS_FOUND");
    rejectEntries = true;
    assertThrows(EventPublicationException.class, () -> service.scan(input));
  }

  @Test
  void manifestPublishFailurePropagates() {
    var input = event(BUCKET, MANIFEST_KEY, VERSION, "NO_THREATS_FOUND");
    failPublish = true;
    assertThrows(IllegalStateException.class, () -> service.scan(input));
  }

  @Test
  void cleanReceiptPublishesOnlyReceiptReadyWithExactObject() throws Exception {
    assertEquals("ReceiptReady",
        service.scan(event(BUCKET, RECEIPT_KEY, VERSION, "NO_THREATS_FOUND")));
    assertEquals(List.of("ReceiptReady"), types());
    assertEquals(INGEST_SOURCE, sent.getFirst().entries().getFirst().source());
    assertEquals(JSON.valueToTree(Map.of(
        "schemaVersion", 1,
        "releaseId", RECEIPT_RELEASE,
        "dataset", "add_gb_royalmailaddress",
        "object", Map.of("bucket", BUCKET, "key", RECEIPT_KEY, "versionId", VERSION))),
        detail(0));
  }

  @Test
  void infectedReceiptPublishesOnlyRejectionWithThreatNames() throws Exception {
    service.scan(eventWithThreats(BUCKET, RECEIPT_KEY, VERSION));
    assertEquals(List.of("StepRejected"), types());
    assertEquals("MALWARE_FOUND", detail(0).path("code").asText());
    assertEquals("receipt-scan", detail(0).path("step").asText());
    assertEquals("Example.Malware", detail(0).path("message").asText());
    assertEquals(RECEIPT_KEY, detail(0).path("object").path("key").asText());
    assertEquals(VERSION, detail(0).path("object").path("versionId").asText());
  }

  @ParameterizedTest(name = "receipt result: {0}")
  @ValueSource(strings = {"FAILED", "UNSUPPORTED", "ACCESS_DENIED", "UNKNOWN"})
  void inconclusiveReceiptsPublishOnlyRejections(String result) throws Exception {
    service.scan(event(BUCKET, RECEIPT_KEY, VERSION, result));
    assertEquals(List.of("StepRejected"), types());
    assertEquals("SCAN_INCONCLUSIVE", detail(0).path("code").asText());
    assertEquals("receipt-scan", detail(0).path("step").asText());
    assertEquals(result, detail(0).path("message").asText());
  }

  @ParameterizedTest(name = "{0}")
  @MethodSource("invalidReceiptEvents")
  void receiptRequiresIngestBucketAndVersion(
      String scenario, Map<String, Object> input) throws Exception {
    service.scan(input);
    assertEquals(List.of("StepRejected"), types());
    assertEquals("INVALID_INPUT", detail(0).path("code").asText());
    assertEquals("receipt-scan", detail(0).path("step").asText());
    assertEquals(RECEIPT_KEY, detail(0).path("object").path("key").asText());
  }

  @Test
  void duplicateReceiptsAreRepublished() throws Exception {
    var input = event(BUCKET, RECEIPT_KEY, VERSION, "NO_THREATS_FOUND");
    service.scan(input);
    service.scan(input);
    assertEquals(List.of("ReceiptReady", "ReceiptReady"), types());
  }

  @Test
  void receiptRejectedPublicationPropagates() {
    var input = event(BUCKET, RECEIPT_KEY, VERSION, "NO_THREATS_FOUND");
    rejectEntries = true;
    assertThrows(EventPublicationException.class, () -> service.scan(input));
  }

  @Test
  void scannedZipPreservesUuidReleaseAndExactObject() throws Exception {
    String releaseId = "f3d8c9a7-7b4e-4f62-a9d1-8c5e72f1b6a4";
    String key = "Scanned/" + releaseId + "/add_isl_royalmailaddress.zip";
    service.scan(event(BUCKET, key, VERSION, "NO_THREATS_FOUND"));
    assertEquals(List.of("ExtractRequested"), types());
    JsonNode payload = detail(0);
    assertEquals(releaseId, payload.path("releaseId").asText());
    assertEquals("add_isl_royalmailaddress", payload.path("dataset").asText());
    assertEquals(BUCKET, payload.path("object").path("bucket").asText());
    assertEquals(key, payload.path("object").path("key").asText());
    assertEquals(VERSION, payload.path("object").path("versionId").asText());
  }

  @Test
  void cleanZipPublishesExtractRequestedWithExactVersion() throws Exception {
    assertEquals("ExtractRequested", service.scan(event(BUCKET, KEY, VERSION, "NO_THREATS_FOUND")));

    assertEquals(List.of("ExtractRequested"), types());
    JsonNode d = detail(0);
    assertEquals(INGEST_SOURCE, sent.getFirst().entries().getFirst().source());
    assertEquals("bus", sent.getFirst().entries().getFirst().eventBusName());
    assertTrue(d.get("schemaVersion").isInt());
    assertEquals(1, d.get("schemaVersion").intValue());
    assertEquals("20780-131061", d.get("releaseId").asText());
    assertEquals("add_gb_royalmailaddress", d.get("dataset").asText());
    assertEquals(BUCKET, d.path("object").get("bucket").asText());
    assertEquals(KEY, d.path("object").get("key").asText());
    assertEquals(VERSION, d.path("object").get("versionId").asText());
  }

  @Test
  void cleanCsvPublishesDatasetReadyWithExactObjectVersion() throws Exception {
    assertEquals(
        "DatasetReady", service.scan(event(CLEAN_BUCKET, CSV_KEY, VERSION, "NO_THREATS_FOUND")));

    assertEquals(List.of("DatasetReady"), types());
    JsonNode d = detail(0);
    assertEquals(INGEST_SOURCE, sent.getFirst().entries().getFirst().source());
    assertTrue(d.get("schemaVersion").isInt());
    assertEquals(1, d.get("schemaVersion").intValue());
    assertEquals("20780-131061", d.get("releaseId").asText());
    assertEquals("add_gb_royalmailaddress", d.get("dataset").asText());
    assertEquals(CLEAN_BUCKET, d.path("object").get("bucket").asText());
    assertEquals(CSV_KEY, d.path("object").get("key").asText());
    assertEquals(VERSION, d.path("object").get("versionId").asText());
  }

  @Test
  void cleanCsvInIngestBucketIsRejected() throws Exception {
    service.scan(event(BUCKET, CSV_KEY, VERSION, "NO_THREATS_FOUND"));

    assertEquals(List.of("StepRejected"), types());
    assertEquals("INVALID_INPUT", detail(0).get("code").asText());
    assertFalse(types().contains("DatasetReady"));
  }

  @Test
  void csvMalwareIsRejectedAsCsvScanWithThreatNames() throws Exception {
    service.scan(eventWithThreats(CLEAN_BUCKET, CSV_KEY, VERSION));

    assertEquals(List.of("StepRejected"), types());
    JsonNode d = detail(0);
    assertEquals("csv-scan", d.get("step").asText());
    assertEquals("MALWARE_FOUND", d.get("code").asText());
    assertEquals("Example.Malware", d.get("message").asText());
    assertEquals(CLEAN_BUCKET, d.path("object").get("bucket").asText());
    assertFalse(types().contains("DatasetReady"));
  }

  @ParameterizedTest(name = "CSV result: {0}")
  @ValueSource(strings = {"UNSUPPORTED", "ACCESS_DENIED", "FAILED", "UNKNOWN"})
  void csvInconclusiveResultsAreRejectedWithoutProgressing(String result) throws Exception {
    service.scan(event(CLEAN_BUCKET, CSV_KEY, VERSION, result));
    assertEquals(List.of("StepRejected"), types());
    assertEquals("SCAN_INCONCLUSIVE", detail(0).get("code").asText());
    assertEquals("csv-scan", detail(0).get("step").asText());
    assertEquals(result, detail(0).get("message").asText());
  }

  @Test
  void repeatedCsvEventPublishesDatasetReadyForEachDelivery() throws Exception {
    var e = event(CLEAN_BUCKET, CSV_KEY, VERSION, "NO_THREATS_FOUND");
    service.scan(e);
    service.scan(e);

    assertEquals(List.of("DatasetReady", "DatasetReady"), types());
  }

  @Test
  void capturedGuardDutyFixtureParses() throws Exception {
    try (InputStream in =
        getClass().getResourceAsStream("/fixtures/guardduty-scan-result-cidev.json")) {
      Map<String, Object> event = JSON.readValue(in, new TypeReference<>() {});
      assertEquals("ExtractRequested", service.scan(event));
    }
  }

  @Test
  void threatsFoundPublishesMalwareFound() throws Exception {
    service.scan(event(BUCKET, KEY, VERSION, "THREATS_FOUND"));

    assertEquals(List.of("StepRejected"), types());
    assertEquals("MALWARE_FOUND", detail(0).get("code").asText());
    assertEquals("zip-scan", detail(0).get("step").asText());
  }

  @ParameterizedTest(name = "ZIP result: {0}")
  @ValueSource(strings = {"UNSUPPORTED", "ACCESS_DENIED", "FAILED", "SOMETHING_NEW"})
  void inconclusiveResultsPublishScanInconclusive(String result) throws Exception {
    service.scan(event(BUCKET, KEY, VERSION, result));
    assertEquals(List.of("StepRejected"), types());
    assertEquals("SCAN_INCONCLUSIVE", detail(0).get("code").asText());
    assertEquals("zip-scan", detail(0).get("step").asText());
    assertEquals(result, detail(0).get("message").asText());
  }

  @ParameterizedTest(name = "{0}")
  @MethodSource("invalidEvents")
  void invalidInputPublishesInvalidInput(
      String scenario, Map<String, Object> input) throws Exception {
    service.scan(input);
    assertEquals(List.of("StepRejected"), types());
    assertEquals("INVALID_INPUT", detail(0).get("code").asText());
  }

  @Test
  void duplicateEventsAreNotSuppressed() throws Exception {
    var e = event(BUCKET, KEY, VERSION, "NO_THREATS_FOUND");
    service.scan(e);
    service.scan(e);
    assertEquals(List.of("ExtractRequested", "ExtractRequested"), types());
  }

  @Test
  void publishFailurePropagates() {
    failPublish = true;
    var input = event(BUCKET, KEY, VERSION, "NO_THREATS_FOUND");
    assertThrows(
        IllegalStateException.class, () -> service.scan(input));
  }

  @Test
  void rejectedEntryFromEventBridgeFailsInvocation() {
    rejectEntries = true;
    var input = event(BUCKET, KEY, VERSION, "NO_THREATS_FOUND");
    assertThrows(
        EventPublicationException.class, () -> service.scan(input));
  }

  @ParameterizedTest(name = "dataset: {0}")
  @ValueSource(strings = {
      "add_gb_royalmailaddress", "add_isl_royalmailaddress"
  })
  void eachDatasetIsRoutedIndependently(String dataset) throws Exception {
    service.scan(event(BUCKET, "zips/r-1/" + dataset + ".zip", VERSION, "NO_THREATS_FOUND"));
    assertEquals(List.of("ExtractRequested"), types());
    assertEquals(dataset, detail(0).get("dataset").asText());
  }

  @ParameterizedTest(name = "{0}")
  @MethodSource("rejectedEvents")
  void rejectedEventsNeverCarryExtractRequested(
      String scenario, Map<String, Object> input) throws Exception {
    service.scan(input);
    assertEquals(List.of("StepRejected"), types());
  }

  @Test
  void emptyEventIsInvalidInput() throws Exception {
    assertEquals("StepRejected:INVALID_INPUT", service.scan(Map.of()));
    assertEquals("INVALID_INPUT", detail(0).get("code").asText());
  }

  @Test
  void blankVersionIdIsInvalidInput() throws Exception {
    assertEquals(
        "StepRejected:INVALID_INPUT",
        service.scan(event(BUCKET, KEY, " ", "NO_THREATS_FOUND")));
  }

  private static Stream<Arguments> invalidManifestEvents() {
    return Stream.of(
        Arguments.of("manifest in wrong bucket",
            event(CLEAN_BUCKET, MANIFEST_KEY, VERSION, "NO_THREATS_FOUND")),
        Arguments.of("manifest missing version",
            event(BUCKET, MANIFEST_KEY, null, "NO_THREATS_FOUND")),
        Arguments.of("manifest blank version",
            event(BUCKET, MANIFEST_KEY, " ", "NO_THREATS_FOUND")),
        Arguments.of("manifest invalid date",
            event(BUCKET, "acquisitions/2026-02-30/" + MANIFEST_RELEASE + ".json",
                VERSION, "NO_THREATS_FOUND")));
  }

  private static Stream<Arguments> invalidReceiptEvents() {
    return Stream.of(
        Arguments.of("receipt in wrong bucket",
            event(CLEAN_BUCKET, RECEIPT_KEY, VERSION, "NO_THREATS_FOUND")),
        Arguments.of("receipt missing version",
            event(BUCKET, RECEIPT_KEY, null, "NO_THREATS_FOUND")));
  }

  private static Stream<Arguments> invalidEvents() {
    return Stream.of(
        Arguments.of("unknown bucket",
            event("other-bucket", KEY, VERSION, "NO_THREATS_FOUND")),
        Arguments.of("unknown dataset",
            event(BUCKET, "zips/20780-131061/unknown.zip", VERSION, "NO_THREATS_FOUND")),
        Arguments.of("unsafe path",
            event(BUCKET, "zips/../add_gb_builtaddress.zip", VERSION, "NO_THREATS_FOUND")),
        Arguments.of("incorrect extension for prefix",
            event(BUCKET, "zips/20780-131061/add_gb_royalmailaddress.csv", VERSION, "NO_THREATS_FOUND")),
        Arguments.of("missing version",
            event(BUCKET, KEY, null, "NO_THREATS_FOUND")),
        Arguments.of("unexpected source",
            Map.<String, Object>of("source", "aws.s3", "detail", Map.of())));
  }

  private static Stream<Arguments> rejectedEvents() {
    return Stream.of(
        Arguments.of("infected ZIP", event(BUCKET, KEY, VERSION, "THREATS_FOUND")),
        Arguments.of("failed scan", event(BUCKET, KEY, VERSION, "FAILED")),
        Arguments.of("unknown bucket", event("other", KEY, VERSION, "NO_THREATS_FOUND")));
  }

  private List<String> types() {
    return sent.stream().map(r -> r.entries().getFirst().detailType()).toList();
  }

  private JsonNode detail(int i) throws Exception {
    return JSON.readTree(sent.get(i).entries().getFirst().detail());
  }

  private static Map<String, Object> eventWithThreats(String bucket, String key, String version) {
    var object = new HashMap<String, Object>();
    object.put("bucketName", bucket);
    object.put("objectKey", key);
    object.put("versionId", version);
    return Map.of(
        "id",
        "evt-1",
        "source",
        "aws.guardduty",
        "detail-type",
        "GuardDuty Malware Protection Object Scan Result",
        "detail",
        Map.of(
            "s3ObjectDetails",
            object,
            "scanStatus",
            "COMPLETED",
            "scanResultDetails",
            Map.of(
                "scanResultStatus",
                "THREATS_FOUND",
                "threats",
                List.of(Map.of("name", "Example.Malware")))));
  }

  private static Map<String, Object> event(
      String bucket, String key, String version, String result) {
    var object = new HashMap<String, Object>();
    object.put("bucketName", bucket);
    object.put("objectKey", key);
    if (version != null) object.put("versionId", version);
    return Map.of(
        "id", "evt-1",
        "source", "aws.guardduty",
        "detail-type", "GuardDuty Malware Protection Object Scan Result",
        "detail",
            Map.of(
                "s3ObjectDetails", object,
                "scanStatus", "COMPLETED",
                "scanResultDetails", Map.of("scanResultStatus", result)));
  }
}
