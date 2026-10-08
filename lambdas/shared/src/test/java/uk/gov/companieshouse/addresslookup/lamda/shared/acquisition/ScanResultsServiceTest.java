package uk.gov.companieshouse.addresslookup.lamda.shared.acquisition;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.JsonNode;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import software.amazon.awssdk.services.eventbridge.EventBridgeClient;
import software.amazon.awssdk.services.eventbridge.model.PutEventsRequest;
import software.amazon.awssdk.services.eventbridge.model.PutEventsResponse;

import java.io.InputStream;
import java.lang.reflect.Proxy;
import java.time.Duration;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.function.Consumer;

import static org.junit.jupiter.api.Assertions.*;
import static uk.gov.companieshouse.addresslookup.lamda.shared.runtime.RuntimeSupport.JSON;

class ScanResultsServiceTest {
  private static final String BUCKET = "ingest-bucket";
  private static final String KEY = "zips/20780-131061/add_gb_builtaddress.zip";
  private static final String VERSION = "3HL4kqtJlcpXroDTDmJ.rmSpXd3dIbrHY";

  private final List<PutEventsRequest> sent = new ArrayList<>();
  private boolean failPublish;
  private boolean rejectEntries;
  private ScanResultsService service;

  @BeforeEach
  @SuppressWarnings("unchecked")
  void setUp() {
    EventBridgeClient client =
        (EventBridgeClient)
            Proxy.newProxyInstance(
                getClass().getClassLoader(),
                new Class<?>[] {EventBridgeClient.class},
                (proxy, method, args) -> {
                  if (!method.getName().equals("putEvents")) return null;
                  if (failPublish) throw new IllegalStateException("EventBridge unavailable");
                  var builder = PutEventsRequest.builder();
                  ((Consumer<PutEventsRequest.Builder>) args[0]).accept(builder);
                  sent.add(builder.build());
                  return PutEventsResponse.builder().failedEntryCount(rejectEntries ? 1 : 0).build();
                });
    var properties =
        new AcquisitionProperties(
            "source", "scanned", BUCKET, "bus", null, null, 1, 1, 1,
            Duration.ofSeconds(1), Duration.ofSeconds(1));
    service = new ScanResultsService(new Commands(client, "bus", JSON), properties);
  }

  @Test
  void cleanZipPublishesExtractRequestedWithExactVersion() throws Exception {
    assertEquals("ExtractRequested", service.scan(event(BUCKET, KEY, VERSION, "NO_THREATS_FOUND")));

    assertEquals(List.of("ExtractRequested"), types());
    JsonNode d = detail(0);
    assertEquals("20780-131061", d.get("releaseId").asText());
    assertEquals("add_gb_builtaddress", d.get("dataset").asText());
    assertEquals(BUCKET, d.get("bucket").asText());
    assertEquals(KEY, d.get("key").asText());
    assertEquals(VERSION, d.get("versionId").asText());
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
  }

  @Test
  void inconclusiveResultsPublishScanInconclusive() throws Exception {
    for (String result : List.of("UNSUPPORTED", "ACCESS_DENIED", "FAILED", "SOMETHING_NEW")) {
      sent.clear();
      service.scan(event(BUCKET, KEY, VERSION, result));
      assertEquals(List.of("StepRejected"), types());
      assertEquals("SCAN_INCONCLUSIVE", detail(0).get("code").asText());
      assertEquals(result, detail(0).get("scanResult").asText());
    }
  }

  @Test
  void invalidInputPublishesInvalidInput() throws Exception {
    var cases =
        List.of(
            event("other-bucket", KEY, VERSION, "NO_THREATS_FOUND"),
            event(BUCKET, "zips/20780-131061/unknown.zip", VERSION, "NO_THREATS_FOUND"),
            event(BUCKET, "zips/../add_gb_builtaddress.zip", VERSION, "NO_THREATS_FOUND"),
            event(BUCKET, "zips/20780-131061/add_gb_builtaddress.csv", VERSION, "NO_THREATS_FOUND"),
            event(BUCKET, KEY, null, "NO_THREATS_FOUND"),
            Map.<String, Object>of("source", "aws.s3", "detail", Map.of()));
    for (var c : cases) {
      sent.clear();
      service.scan(c);
      assertEquals(List.of("StepRejected"), types());
      assertEquals("INVALID_INPUT", detail(0).get("code").asText());
    }
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
    assertThrows(
        Exception.class, () -> service.scan(event(BUCKET, KEY, VERSION, "NO_THREATS_FOUND")));
  }

  @Test
  void rejectedEntryFromEventBridgeFailsInvocation() {
    rejectEntries = true;
    assertThrows(
        IllegalArgumentException.class,
        () -> service.scan(event(BUCKET, KEY, VERSION, "NO_THREATS_FOUND")));
  }

  @Test
  void eachDatasetIsRoutedIndependently() throws Exception {
    for (String dataset :
        List.of(
            "add_gb_builtaddress",
            "add_gb_royalmailaddress",
            "add_isl_builtaddress",
            "add_isl_royalmailaddress")) {
      service.scan(event(BUCKET, "zips/r1/" + dataset + ".zip", VERSION, "NO_THREATS_FOUND"));
    }
    assertEquals(4, sent.size());
    assertEquals("add_isl_royalmailaddress", detail(3).get("dataset").asText());
  }

  @Test
  void rejectedEventsNeverCarryExtractRequested() throws Exception {
    service.scan(event(BUCKET, KEY, VERSION, "THREATS_FOUND"));
    service.scan(event(BUCKET, KEY, VERSION, "FAILED"));
    service.scan(event("other", KEY, VERSION, "NO_THREATS_FOUND"));
    assertFalse(types().contains("ExtractRequested"));
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

  @Test
  void constructorRequiresIngestBucket() {
    var props =
        new AcquisitionProperties(
            "source", "scanned", null, "bus", null, null, 1, 1, 1,
            Duration.ofSeconds(1), Duration.ofSeconds(1));
    assertThrows(
        IllegalArgumentException.class, () -> new ScanResultsService(null, props));
  }

  private List<String> types() {
    return sent.stream().map(r -> r.entries().get(0).detailType()).toList();
  }

  private JsonNode detail(int i) throws Exception {
    return JSON.readTree(sent.get(i).entries().get(0).detail());
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
