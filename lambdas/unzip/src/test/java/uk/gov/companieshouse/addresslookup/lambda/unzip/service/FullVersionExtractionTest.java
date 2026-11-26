package uk.gov.companieshouse.addresslookup.lambda.unzip.service;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

import com.amazonaws.services.lambda.runtime.Context;
import com.fasterxml.jackson.databind.JsonNode;
import java.io.ByteArrayOutputStream;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.zip.CRC32;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import software.amazon.awssdk.services.s3.S3Client;
import software.amazon.awssdk.services.s3.model.GetObjectRequest;
import software.amazon.awssdk.services.s3.model.ListObjectsV2Request;
import software.amazon.awssdk.services.s3.model.ListObjectsV2Response;
import uk.gov.companieshouse.addresslookup.lambda.shared.acquisition.AcquisitionProperties;
import uk.gov.companieshouse.addresslookup.lambda.shared.acquisition.ExtractionProperties;
import uk.gov.companieshouse.addresslookup.lambda.shared.runtime.RuntimeSupport;
import uk.gov.companieshouse.addresslookup.lambda.shared.testsupport.FakeS3;
import uk.gov.companieshouse.addresslookup.lambda.shared.testsupport.TestContext;
import uk.gov.companieshouse.release.model.DatasetCatalog;

final class FullVersionExtractionTest {
  private static final String PREFIX = "landing/full/24833/150450/";
  private static final String DATASET = "add_isl_royalmailaddress";
  private static final String ZIP = PREFIX + DATASET + ".zip";
  private static final String SUMMARY = PREFIX + DATASET + "_orderSummary.json";
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
    fullContext = TestContext.withRemainingMillis(900_000);
    service = new ExtractionComponent(s3,
        new AcquisitionProperties("landing-bucket", "scanned", "ingest", "events", "key",
            "packages", 1024 * 1024, null, null, 1024 * 1024, 20,
            Duration.ofSeconds(20), Duration.ofSeconds(60)),
        new ExtractionProperties(
            new ExtractionProperties.Feed("24833", "control/", "landing/"), null));
    fake.objects.put(ZIP, zip(Map.of(DATASET + ".csv", header() + "\n")));
    fake.objects.put(SUMMARY, "{}".getBytes(StandardCharsets.UTF_8));
    fake.versions.put(ZIP, "zip-v1");
    fake.versions.put(SUMMARY, "summary-v1");
    fake.objects.put(PREFIX + "manifest.json", manifest());
    fake.objects.put("control/active.json", "unchanged".getBytes(StandardCharsets.UTF_8));
  }

  @Test
  void waitsForScanThenResumesBothFiles() throws Exception {
    assertEquals("IN_PROGRESS", service.unzip(REQUEST, TestContext.withRemainingMillis(900_000)));
    assertFalse(fake.objects.containsKey(CSV));
    assertEquals("PENDING", state().path("files").path(ZIP).path("status").asText());
    assertEquals("zip-v1", state().path("files").path(ZIP).path("versionId").asText());
    assertEquals("summary-v1", state().path("files").path(SUMMARY).path("versionId").asText());
    assertEquals(2, state().path("files").size());

    fake.scanStatus.put(ZIP, "NO_THREATS_FOUND");
    assertEquals("IN_PROGRESS", service.unzip(REQUEST, TestContext.withRemainingMillis(900_000)));
    assertTrue(fake.objects.containsKey(CSV));
    verify(s3).getObject(argThat((GetObjectRequest request) ->
        ZIP.equals(request.key()) && "zip-v1".equals(request.versionId())));
    assertEquals("UNPACKED", state().path("files").path(ZIP).path("status").asText());
    assertEquals("PENDING", state().path("files").path(SUMMARY).path("status").asText());

    fake.scanStatus.put(SUMMARY, "NO_THREATS_FOUND");
    fake.scanStatus.put(ZIP, "THREATS_FOUND");
    assertEquals("COMPLETE", service.unzip(REQUEST, TestContext.withRemainingMillis(900_000)));
    assertArrayEquals("{}".getBytes(StandardCharsets.UTF_8), fake.objects.get(OUTPUT_SUMMARY));
    verify(s3).getObject(argThat((GetObjectRequest request) ->
        SUMMARY.equals(request.key()) && "summary-v1".equals(request.versionId())));
    assertTrue(state().path("complete").asBoolean());
    assertEquals(CSV, state().path("files").path(ZIP).path("outputKeys").get(0).asText());
    assertEquals(OUTPUT_SUMMARY,
        state().path("files").path(SUMMARY).path("outputKeys").get(0).asText());
    assertEquals("COMPLETE", service.unzip(REQUEST, TestContext.withRemainingMillis(900_000)));
    assertEquals(2, fake.completedKeys.size());
    assertEquals("unchanged", new String(fake.objects.get("control/active.json"),
        StandardCharsets.UTF_8));
    verify(s3, never()).getObject(argThat((GetObjectRequest request) ->
        request.key() != null && request.key().startsWith("control/")));
  }

  @Test
  void threatStopsWithoutUnpacking() throws Exception {
    fake.scanStatus.put(ZIP, "THREATS_FOUND");
    assertEquals("NEEDS_ATTENTION", service.unzip(REQUEST, TestContext.withRemainingMillis(900_000)));
    assertEquals("PENDING", state().path("files").path(ZIP).path("status").asText());
    assertFalse(fake.objects.containsKey(CSV));
  }

  @Test
  void resumesFromDiscoveredFilesRatherThanIncludingNewObjects() throws Exception {
    assertEquals("IN_PROGRESS", service.unzip(REQUEST, fullContext));
    fake.objects.put(PREFIX + "unexpected.txt", "unexpected".getBytes(StandardCharsets.UTF_8));

    assertEquals("IN_PROGRESS", service.unzip(REQUEST, fullContext));
    assertEquals(2, state().path("files").size());
    assertEquals("zip-v1", state().path("files").path(ZIP).path("versionId").asText());
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
        () -> service.unzip(REQUEST, fullContext));
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
    assertEquals("IN_PROGRESS", service.unzip(REQUEST, context));
    assertEquals("UNPACKED", state().path("files").path(ZIP).path("status").asText());
    assertEquals("PENDING", state().path("files").path(SUMMARY).path("status").asText());
    assertEquals("COMPLETE", service.unzip(REQUEST, TestContext.withRemainingMillis(900_000)));
    assertEquals(2, fake.completedKeys.size());
  }

  @Test
  void failedZipRemainsPendingAndCanBeRetried() throws Exception {
    fake.scanStatus.put(ZIP, "NO_THREATS_FOUND");
    fake.objects.put(ZIP, zip(Map.of("../bad.csv", "bad")));
    assertThrows(Exception.class, () -> service.unzip(REQUEST, fullContext));
    assertEquals("PENDING", state().path("files").path(ZIP).path("status").asText());
    assertFalse(state().path("complete").asBoolean());
  }

  @Test
  void rejectsHeaderMismatchWithoutMarkingFileUnpacked() throws Exception {
    fake.scanStatus.put(ZIP, "NO_THREATS_FOUND");
    fake.objects.put(ZIP, zip(Map.of(DATASET + ".csv", "wrong,header\n")));
    assertThrows(ExtractionComponent.CsvHeaderException.class,
        () -> service.unzip(REQUEST, fullContext));
    assertEquals("PENDING", state().path("files").path(ZIP).path("status").asText());
    assertFalse(fake.objects.containsKey(CSV));
  }

  @Test
  void rejectsHighlyCompressedZipWithoutMarkingFileUnpacked() throws Exception {
    fake.scanStatus.put(ZIP, "NO_THREATS_FOUND");
    fake.objects.put(ZIP, zip(Map.of(DATASET + ".csv", header() + "\n" + "x".repeat(30_000))));
    assertThrows(java.util.zip.ZipException.class,
        () -> service.unzip(REQUEST, fullContext));
    assertEquals("PENDING", state().path("files").path(ZIP).path("status").asText());
    assertFalse(state().path("complete").asBoolean());
  }

  @Test
  void rejectsArchiveExceedingTotalCompressionRatio() throws Exception {
    fake.scanStatus.put(ZIP, "NO_THREATS_FOUND");
    var entries = new LinkedHashMap<String, String>();
    entries.put(DATASET + "_first.csv", "a,b\n");
    entries.put(DATASET + "_second.csv", "x".repeat(1_000));
    fake.objects.put(ZIP, zip(entries));
    assertThrows(java.util.zip.ZipException.class,
        () -> service.unzip(REQUEST, fullContext));
    assertEquals("PENDING", state().path("files").path(ZIP).path("status").asText());
  }

  @ParameterizedTest
  @ValueSource(strings = {"notes.txt", "../bad.csv", "nested/add_isl_royalmailaddress.csv",
      "add_isl_royalmailaddress_notes.json", "dir\\file.csv"})
  void rejectsUnexpectedOrUnsafeZipEntry(String name) throws Exception {
    fake.scanStatus.put(ZIP, "NO_THREATS_FOUND");
    fake.objects.put(ZIP, zip(Map.of(name, "bad\n")));
    assertThrows(ExtractionComponent.ArchiveValidationException.class,
        () -> service.unzip(REQUEST, fullContext));
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
    assertThrows(ExtractionComponent.ArchiveValidationException.class,
        () -> service.unzip(REQUEST, fullContext));
    assertEquals("PENDING", state().path("files").path(ZIP).path("status").asText());
    assertFalse(state().path("complete").asBoolean());
  }

  @Test
  void rejectsMoreThanOneHundredEntries() throws Exception {
    fake.scanStatus.put(ZIP, "NO_THREATS_FOUND");
    var entries = new LinkedHashMap<String, String>();
    for (int i = 0; i < 101; i++) {
      entries.put(DATASET + "_part" + i + ".csv", "a,b\n" + i + "\n");
    }
    fake.objects.put(ZIP, zip(entries));
    assertThrows(ExtractionComponent.ArchiveValidationException.class,
        () -> service.unzip(REQUEST, fullContext));
    assertEquals("PENDING", state().path("files").path(ZIP).path("status").asText());
  }

  @Test
  void rejectsOversizedStoredEntry() throws Exception {
    fake.scanStatus.put(ZIP, "NO_THREATS_FOUND");
    byte[] content = "x".repeat(1024 * 1024 + 1).getBytes(StandardCharsets.UTF_8);
    var bytes = new ByteArrayOutputStream();
    var crc = new CRC32();
    crc.update(content);
    try (var zip = new ZipOutputStream(bytes)) {
      var entry = new ZipEntry(DATASET + "_large.csv");
      entry.setMethod(ZipEntry.STORED);
      entry.setSize(content.length);
      entry.setCompressedSize(content.length);
      entry.setCrc(crc.getValue());
      zip.putNextEntry(entry);
      zip.write(content);
      zip.closeEntry();
    }
    fake.objects.put(ZIP, bytes.toByteArray());
    assertThrows(java.util.zip.ZipException.class,
        () -> service.unzip(REQUEST, fullContext));
    assertEquals("PENDING", state().path("files").path(ZIP).path("status").asText());
  }

  @Test
  void rejectsMissingPrimaryCsv() throws Exception {
    fake.scanStatus.put(ZIP, "NO_THREATS_FOUND");
    fake.objects.put(ZIP, zip(Map.of(DATASET + "_extra.csv", "a,b\n")));
    assertThrows(ExtractionComponent.MissingPrimaryCsvException.class,
        () -> service.unzip(REQUEST, fullContext));
    assertEquals("PENDING", state().path("files").path(ZIP).path("status").asText());
  }

  @Test
  void rejectsMalformedZip() throws Exception {
    fake.scanStatus.put(ZIP, "NO_THREATS_FOUND");
    byte[] malformed = zip(Map.of(DATASET + ".csv", header() + "\n"));
    malformed[8] = 99;
    fake.objects.put(ZIP, malformed);
    assertThrows(java.util.zip.ZipException.class,
        () -> service.unzip(REQUEST, fullContext));
    assertEquals("PENDING", state().path("files").path(ZIP).path("status").asText());
  }

  @Test
  void refusesUnexpectedVersionOrManifest() {
    var wrongPackage = Map.<String, Object>of("mode", "FULL", "versions",
        Map.of("data_package", "99999", "version_id", "150450"));
    assertThrows(IllegalArgumentException.class,
        () -> service.unzip(wrongPackage, fullContext));
    fake.objects.put(PREFIX + "manifest.json", new String(manifest(), StandardCharsets.UTF_8)
        .replace("\"previous\":null", "\"previous\":\"150449\"")
        .getBytes(StandardCharsets.UTF_8));
    assertThrows(IllegalArgumentException.class,
        () -> service.unzip(REQUEST, fullContext));
    assertFalse(fake.objects.containsKey(STATE));
  }

  @ParameterizedTest
  @ValueSource(strings = {"unexpected.txt", "../other.zip", "nested/add_isl_royalmailaddress.zip"})
  void rejectsUnexpectedS3Input(String name) {
    String key = PREFIX + name;
    fake.objects.put(key, "unexpected".getBytes(StandardCharsets.UTF_8));

    var failure = assertThrows(IllegalArgumentException.class,
        () -> service.unzip(REQUEST, fullContext));
    assertEquals("Unexpected FULL input: " + key, failure.getMessage());
    assertFalse(fake.objects.containsKey(STATE));
  }

  @Test
  void rejectsVersionWithNoS3InputFiles() {
    fake.objects.remove(ZIP);
    fake.objects.remove(SUMMARY);

    var failure = assertThrows(IllegalArgumentException.class,
        () -> service.unzip(REQUEST, fullContext));
    assertEquals("FULL version has no input files", failure.getMessage());
    assertFalse(fake.objects.containsKey(STATE));
  }

  @Test
  void rejectsUnsupportedCouMode() {
    var request = Map.<String, Object>of("mode", "COU");
    var failure = assertThrows(IllegalArgumentException.class,
        () -> service.unzip(request, fullContext));
    assertEquals("Unsupported extraction mode", failure.getMessage());
    assertFalse(fake.objects.containsKey(STATE));
  }

  @Test
  void uploadsPermittedAncillaryCsvAlongsidePrimary() throws Exception {
    fake.scanStatus.put(ZIP, "NO_THREATS_FOUND");
    fake.scanStatus.put(SUMMARY, "NO_THREATS_FOUND");
    var entries = new LinkedHashMap<String, String>();
    entries.put(DATASET + ".csv", header() + "\n");
    entries.put(DATASET + "_extra.csv", "a,b\n");
    fake.objects.put(ZIP, zip(entries));
    assertEquals("COMPLETE", service.unzip(REQUEST, TestContext.withRemainingMillis(900_000)));
    String ancillary = PREFIX + "downloads/" + DATASET + "/" + DATASET + "_extra.csv";
    assertArrayEquals("a,b\n".getBytes(StandardCharsets.UTF_8), fake.objects.get(ancillary));
    assertEquals(2, state().path("files").path(ZIP).path("outputKeys").size());
  }

  private JsonNode state() throws Exception {
    return RuntimeSupport.JSON.readTree(fake.objects.get(STATE));
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
}
