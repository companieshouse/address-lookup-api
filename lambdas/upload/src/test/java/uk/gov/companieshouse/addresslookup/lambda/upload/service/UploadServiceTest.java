package uk.gov.companieshouse.addresslookup.lambda.upload.service;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.argThat;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.amazonaws.services.lambda.runtime.events.SQSEvent;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import uk.gov.companieshouse.addresslookup.lambda.shared.acquisition.Commands;
import uk.gov.companieshouse.addresslookup.lambda.shared.storage.ReleaseStore;
import uk.gov.companieshouse.addresslookup.lambda.shared.testsupport.FakeS3;
import uk.gov.companieshouse.addresslookup.lambda.upload.runtime.UploadProperties;
import uk.gov.companieshouse.addresslookup.lambda.upload.service.StagingLoader.StagedManifest;
import uk.gov.companieshouse.release.model.Dataset;
import uk.gov.companieshouse.release.model.DatasetCatalog;
import uk.gov.companieshouse.release.model.SupplyType;

@ExtendWith(MockitoExtension.class)
final class UploadServiceTest {
  private static final String SCANNED = "scanned";
  private static final String INGEST = "ingest";
  private static final String ID = "12345678-1234-1234-1234-123456789abc";
  private static final String DATASET = "add_gb_royalmailaddress";
  private static final String CSV_KEY = ReleaseStore.extractedCsvKey(ID, DATASET);
  private static final String MANIFEST_KEY = "acquisitions/2026-05-01/" + ID + ".json";
  private static final long ROWS = 2;
  private static final StagedManifest STAGED =
      new StagedManifest(UUID.fromString(ID), SupplyType.FULL, "2.0", ROWS);

  @Mock private StagingLoader loader;
  @Mock private Commands commands;

  private FakeS3 s3;
  private UploadService service;

  @BeforeEach
  void setUp() {
    s3 = new FakeS3();
    service =
        new UploadService(s3.client(), new UploadProperties(SCANNED, INGEST), loader, commands);
    s3.objects.put(MANIFEST_KEY, manifest().getBytes(StandardCharsets.UTF_8));
    s3.objects.put(CSV_KEY, csv(header()).getBytes(StandardCharsets.UTF_8));
    s3.scanStatus.put(CSV_KEY, ReleaseStore.CLEAN);
    s3.scanStatus.put(MANIFEST_KEY, ReleaseStore.CLEAN);
  }

  @Test
  void recordsACleanManifest() throws Exception {
    assertEquals("MANIFEST_RECORDED", service.process(manifestEvent(INGEST)));

    verify(loader).recordManifest(argThat(m -> m.id().equals(UUID.fromString(ID))));
  }

  @Test
  void manifestFromAnotherBucketIsMalformed() {
    assertThrows(InvalidEventException.class, () -> service.process(manifestEvent("other")));
  }

  @Test
  void invalidManifestIsPermanent() {
    s3.objects.put(MANIFEST_KEY, "{}".getBytes(StandardCharsets.UTF_8));

    var e = assertThrows(PermanentFailureException.class, () -> service.process(manifestEvent(INGEST)));

    assertEquals(PermanentFailureException.MANIFEST_INVALID, e.code());
    verify(loader, never()).recordManifest(any());
  }

  @Test
  void manifestStoredUnderWrongTargetDateIsPermanent() {
    s3.objects.put(
        MANIFEST_KEY,
        manifest().replace("2026-05-01", "2026-06-01").getBytes(StandardCharsets.UTF_8));

    var e = assertThrows(PermanentFailureException.class, () -> service.process(manifestEvent(INGEST)));

    assertEquals(PermanentFailureException.MANIFEST_INVALID, e.code());
  }

  @Test
  void loadsCleanCsvAgainstTheRecordedManifest() throws Exception {
    when(loader.stagedManifest(UUID.fromString(ID), Dataset.ADD_GB_ROYALMAILADDRESS))
        .thenReturn(Optional.of(STAGED));
    when(loader.load(any(), any(), any(), any())).thenReturn(true);

    assertEquals("LOADED", service.process(csvEvent(SCANNED)));

    verify(loader)
        .load(
            eq(Dataset.ADD_GB_ROYALMAILADDRESS),
            eq(STAGED),
            eq(CSV_KEY + "@" + FakeS3.DEFAULT_VERSION),
            any());
  }

  @Test
  void reportsAlreadyLoadedWhenLedgerHasFile() throws Exception {
    when(loader.stagedManifest(any(), any())).thenReturn(Optional.of(STAGED));
    when(loader.load(any(), any(), any(), any())).thenReturn(false);

    assertEquals("ALREADY_LOADED", service.process(csvEvent(SCANNED)));
  }

  @Test
  void csvIsRetriedUntilItsManifestIsRecorded() {
    when(loader.stagedManifest(any(), any())).thenReturn(Optional.empty());

    assertThrows(IllegalStateException.class, () -> service.process(csvEvent(SCANNED)));
    verify(loader, never()).load(any(), any(), any(), any());
  }

  @Test
  void pendingScanIsRetried() {
    s3.scanStatus.remove(CSV_KEY);

    assertThrows(IllegalStateException.class, () -> service.process(csvEvent(SCANNED)));
    verify(loader, never()).load(any(), any(), any(), any());
  }

  @Test
  void unCleanScanIsPermanent() {
    s3.scanStatus.put(CSV_KEY, "THREATS_FOUND");

    var e = assertThrows(PermanentFailureException.class, () -> service.process(csvEvent(SCANNED)));

    assertEquals(PermanentFailureException.SCAN_NOT_CLEAN, e.code());
  }

  @Test
  void headerMismatchIsPermanentAndNeverLoads() {
    s3.objects.put(CSV_KEY, csv("udprn,wrong").getBytes(StandardCharsets.UTF_8));
    when(loader.stagedManifest(any(), any())).thenReturn(Optional.of(STAGED));

    var e = assertThrows(PermanentFailureException.class, () -> service.process(csvEvent(SCANNED)));

    assertEquals(PermanentFailureException.HEADER_MISMATCH, e.code());
    verify(loader, never()).load(any(), any(), any(), any());
  }

  @Test
  void oversizedHeaderIsAHeaderMismatch() {
    s3.objects.put(CSV_KEY, "x".repeat(70 * 1024).getBytes(StandardCharsets.UTF_8));
    when(loader.stagedManifest(any(), any())).thenReturn(Optional.of(STAGED));

    var e = assertThrows(PermanentFailureException.class, () -> service.process(csvEvent(SCANNED)));

    assertEquals(PermanentFailureException.HEADER_MISMATCH, e.code());
  }

  @Test
  void toleratesBomAndCrlfHeader() throws Exception {
    s3.objects.put(
        CSV_KEY, ("\uFEFF" + header() + "\r\n1,x\r\n").getBytes(StandardCharsets.UTF_8));
    when(loader.stagedManifest(any(), any())).thenReturn(Optional.of(STAGED));
    when(loader.load(any(), any(), any(), any())).thenReturn(true);

    assertEquals("LOADED", service.process(csvEvent(SCANNED)));
  }

  @Test
  void unsupportedManifestSchemaVersionIsPermanent() {
    when(loader.stagedManifest(any(), any()))
        .thenReturn(Optional.of(new StagedManifest(UUID.fromString(ID), SupplyType.FULL, "3.0", ROWS)));

    var e = assertThrows(PermanentFailureException.class, () -> service.process(csvEvent(SCANNED)));

    assertEquals(PermanentFailureException.SCHEMA_VERSION_MISMATCH, e.code());
  }

  @Test
  void blockedReleaseIsSkipped() throws Exception {
    s3.objects.put(ReleaseStore.blockedKey(ID), "{}".getBytes(StandardCharsets.UTF_8));

    assertEquals("SKIPPED_BLOCKED", service.process(csvEvent(SCANNED)));
    verify(loader, never()).load(any(), any(), any(), any());
  }

  @Test
  void rejectsUnexpectedBucketAndKey() {
    assertThrows(InvalidEventException.class, () -> service.process(csvEvent("other-bucket")));
    assertThrows(
        InvalidEventException.class,
        () -> service.process(csvEvent(SCANNED).replace(CSV_KEY, "Scanned/" + ID + "/other.csv")));
  }

  @Test
  void rejectsUnsupportedEventVersionAndBadIds() {
    assertThrows(
        InvalidEventException.class,
        () -> service.process(csvEvent(SCANNED).replace("\"schemaVersion\":1", "\"schemaVersion\":2")));
    assertThrows(
        InvalidEventException.class,
        () -> service.process(csvEvent(SCANNED).replace(ID, "not-a-uuid")));
    assertThrows(
        InvalidEventException.class,
        () -> service.process(csvEvent(SCANNED).replace(DATASET, "unknown_dataset")));
  }

  @Test
  void versionChangedAfterScanIsMalformed() {
    s3.versions.put(CSV_KEY, "v2");

    var e = assertThrows(InvalidEventException.class, () -> service.process(csvEvent(SCANNED)));

    assertTrue(e.getMessage().contains("version changed"));
  }

  @Test
  void permanentFailurePublishesStepRejectedAndAcknowledges() throws Exception {
    s3.objects.put(CSV_KEY, csv("udprn,wrong").getBytes(StandardCharsets.UTF_8));
    when(loader.stagedManifest(any(), any())).thenReturn(Optional.of(STAGED));

    var response = service.upload(batch(record("bad", csvEvent(SCANNED))), null);

    assertTrue(response.getBatchItemFailures().isEmpty());
    verify(commands)
        .sendIngestion(
            eq("StepRejected"),
            argThat(
                (Map<String, ?> p) ->
                    "upload".equals(p.get("step"))
                        && PermanentFailureException.HEADER_MISMATCH.equals(p.get("code"))
                        && ID.equals(p.get("releaseId"))
                        && DATASET.equals(p.get("dataset"))));
  }

  @Test
  void failedRejectionPublishIsRetried() throws Exception {
    s3.objects.put(CSV_KEY, csv("udprn,wrong").getBytes(StandardCharsets.UTF_8));
    when(loader.stagedManifest(any(), any())).thenReturn(Optional.of(STAGED));
    org.mockito.Mockito.doThrow(new IllegalStateException("bus down"))
        .when(commands)
        .sendIngestion(any(), any());

    var response = service.upload(batch(record("bad", csvEvent(SCANNED))), null);

    assertEquals(1, response.getBatchItemFailures().size());
  }

  @Test
  void transientFailuresAreRetriedIndividually() {
    when(loader.stagedManifest(any(), any())).thenReturn(Optional.empty());

    var response = service.upload(batch(record("pending", csvEvent(SCANNED))), null);

    assertEquals(1, response.getBatchItemFailures().size());
    assertEquals("pending", response.getBatchItemFailures().getFirst().getItemIdentifier());
  }

  @Test
  void unexpectedIllegalArgumentFromADependencyIsRetriedNotRejected() throws Exception {
    when(loader.stagedManifest(any(), any())).thenThrow(new IllegalArgumentException("library bug"));

    var response = service.upload(batch(record("lib", csvEvent(SCANNED))), null);

    assertEquals(1, response.getBatchItemFailures().size());
    verify(commands, never()).sendIngestion(any(), any());
  }

  @Test
  void invalidEventWithReleaseIdIsRejectedAndAcknowledged() throws Exception {
    var response = service.upload(batch(record("bad", csvEvent("other-bucket"))), null);

    assertTrue(response.getBatchItemFailures().isEmpty());
    verify(commands)
        .sendIngestion(
            eq("StepRejected"),
            argThat(
                (Map<String, ?> p) ->
                    PermanentFailureException.INVALID_EVENT.equals(p.get("code"))
                        && ID.equals(p.get("releaseId"))));
  }

  @Test
  void invalidEventWithoutReleaseIdIsDroppedAndAcknowledged() throws Exception {
    var response =
        service.upload(
            batch(record("garbage", "not json"), record("badId", csvEvent(SCANNED).replace(ID, "x"))),
            null);

    assertTrue(response.getBatchItemFailures().isEmpty());
    verify(commands, never()).sendIngestion(any(), any());
  }

  private static SQSEvent batch(SQSEvent.SQSMessage... records) {
    var batch = new SQSEvent();
    batch.setRecords(List.of(records));
    return batch;
  }

  private static SQSEvent.SQSMessage record(String id, String body) {
    var message = new SQSEvent.SQSMessage();
    message.setMessageId(id);
    message.setBody(body);
    return message;
  }

  private static String csvEvent(String bucket) {
    return """
        {"detail-type":"DatasetReady","detail":{"schemaVersion":1,"releaseId":"%s","dataset":"%s",
        "object":{"bucket":"%s","key":"%s","versionId":"v1"}}}
        """.formatted(ID, DATASET, bucket, CSV_KEY);
  }

  private static String manifestEvent(String bucket) {
    return """
        {"detail-type":"ManifestReady","detail":{"schemaVersion":1,"releaseId":"%s",
        "object":{"bucket":"%s","key":"%s","versionId":"v1"}}}
        """.formatted(ID, bucket, MANIFEST_KEY);
  }

  private static String header() {
    return DatasetCatalog.table(Dataset.ADD_GB_ROYALMAILADDRESS).header();
  }

  private static String csv(String header) {
    return header + "\n1,x\n2,y\n";
  }

  private static String manifest() {
    return """
        {"releaseId":"%s","mode":"FULL","target":"2026-05-01","dataPackageId":"1","versionId":"2",
        "add_gb_royalmailaddress":{"zip":{"fileName":"a.zip","url":"https://x","md5":"229c68b69090208861bc3fdf05009ef6"},
        "summary":{"featureName":"add-gb-royalmailaddress","crs":"http://www.opengis.net/def/crs/EPSG/0/27700",
        "schemaVersion":"2.0","validFromDate":null,"validToDate":"2026-05-01","recordCount":%d}}}
        """.formatted(ID, ROWS);
  }
}
