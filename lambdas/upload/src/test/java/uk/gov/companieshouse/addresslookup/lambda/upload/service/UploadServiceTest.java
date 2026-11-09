package uk.gov.companieshouse.addresslookup.lambda.upload.service;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.argThat;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.amazonaws.services.lambda.runtime.events.SQSEvent;
import java.io.ByteArrayInputStream;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Proxy;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import software.amazon.awssdk.core.ResponseInputStream;
import software.amazon.awssdk.http.AbortableInputStream;
import software.amazon.awssdk.services.s3.S3Client;
import software.amazon.awssdk.services.s3.model.GetObjectRequest;
import software.amazon.awssdk.services.s3.model.GetObjectResponse;
import software.amazon.awssdk.services.s3.model.ListObjectsV2Request;
import software.amazon.awssdk.services.s3.model.ListObjectsV2Response;
import software.amazon.awssdk.services.s3.model.S3Object;
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
    String event = manifestEvent("other");
    assertThrows(InvalidEventException.class, () -> service.process(event));
  }

  @Test
  void invalidManifestIsPermanent() {
    s3.objects.put(MANIFEST_KEY, "{}".getBytes(StandardCharsets.UTF_8));
    String event = manifestEvent(INGEST);

    var e = assertThrows(PermanentFailureException.class, () -> service.process(event));

    assertEquals(PermanentFailureException.MANIFEST_INVALID, e.code());
    verify(loader, never()).recordManifest(any());
  }

  @Test
  void manifestWithoutDatasetsIsPermanent() {
    s3.objects.put(
        MANIFEST_KEY,
        """
        {"releaseId":"%s","mode":"FULL","target":"2026-05-01","dataPackageId":"1","versionId":"2"}
        """
            .formatted(ID)
            .getBytes(StandardCharsets.UTF_8));
    String event = manifestEvent(INGEST);

    var e = assertThrows(PermanentFailureException.class, () -> service.process(event));

    assertEquals(PermanentFailureException.MANIFEST_INVALID, e.code());
    verify(loader, never()).recordManifest(any());
  }

  @Test
  void rejectedCsvBodyIsAbortedBeforeItIsClosed() {
    s3.objects.put(CSV_KEY, csv("udprn,wrong").getBytes(StandardCharsets.UTF_8));
    when(loader.stagedManifest(any(), any())).thenReturn(Optional.of(STAGED));
    var events = new ArrayList<String>();
    var fake = s3.client();
    var client =
        (S3Client)
            Proxy.newProxyInstance(
                S3Client.class.getClassLoader(),
                new Class<?>[] {S3Client.class},
                (proxy, method, args) -> {
                  if (method.getName().equals("getObject")
                      && args[0] instanceof GetObjectRequest request
                      && CSV_KEY.equals(request.key())) {
                    var body =
                        new ByteArrayInputStream(s3.objects.get(CSV_KEY)) {
                          @Override
                          public void close() {
                            events.add("close");
                          }
                        };
                    return new ResponseInputStream<>(
                        GetObjectResponse.builder().build(),
                        AbortableInputStream.create(body, () -> events.add("abort")));
                  }
                  try {
                    return method.invoke(fake, args);
                  } catch (InvocationTargetException e) {
                    throw e.getCause();
                  }
                });
    var aborting =
        new UploadService(client, new UploadProperties(SCANNED, INGEST), loader, commands);
    String event = csvEvent(SCANNED);

    assertThrows(PermanentFailureException.class, () -> aborting.process(event));

    assertEquals("abort", events.getFirst());
  }

  @Test
  void oversizedManifestIsRejectedForQuarantineNotRetried() throws Exception {
    s3.objects.put(MANIFEST_KEY, new byte[1024 * 1024 + 1]);

    var response = service.upload(batch(sqsMessage("big", manifestEvent(INGEST))));

    assertTrue(response.getBatchItemFailures().isEmpty());
    verify(loader, never()).recordManifest(any());
    verify(commands)
        .sendIngestion(
            eq("StepRejected"),
            argThat((Map<String, ?> p) ->
                PermanentFailureException.MANIFEST_INVALID.equals(p.get("code"))
                    && ID.equals(p.get("releaseId"))));
  }

  @Test
  void manifestStoredUnderWrongTargetDateIsPermanent() {
    s3.objects.put(
        MANIFEST_KEY,
        manifest().replace("2026-05-01", "2026-06-01").getBytes(StandardCharsets.UTF_8));
    String event = manifestEvent(INGEST);

    var e = assertThrows(PermanentFailureException.class, () -> service.process(event));

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
    when(loader.manifestRecorded(UUID.fromString(ID))).thenReturn(false);
    String event = csvEvent(SCANNED);

    assertThrows(IllegalStateException.class, () -> service.process(event));
    verify(loader, never()).load(any(), any(), any(), any());
    verify(commands, never()).sendIngestion(any(), any());
  }

  @Test
  void manifestCommittedBetweenLookupsStillLoadsTheCsv() throws Exception {
    when(loader.stagedManifest(any(), any())).thenReturn(Optional.empty(), Optional.of(STAGED));
    when(loader.manifestRecorded(UUID.fromString(ID))).thenReturn(true);
    when(loader.load(any(), any(), any(), any())).thenReturn(true);

    assertEquals("LOADED", service.process(csvEvent(SCANNED)));
  }

  @Test
  void pendingScanIsRetried() {
    s3.scanStatus.remove(CSV_KEY);
    String event = csvEvent(SCANNED);

    assertThrows(IllegalStateException.class, () -> service.process(event));
    verify(loader, never()).load(any(), any(), any(), any());
  }

  @Test
  void unCleanScanIsPermanent() {
    s3.scanStatus.put(CSV_KEY, "THREATS_FOUND");
    String event = csvEvent(SCANNED);

    var e = assertThrows(PermanentFailureException.class, () -> service.process(event));

    assertEquals(PermanentFailureException.SCAN_NOT_CLEAN, e.code());
  }

  @Test
  void headerMismatchIsPermanentAndNeverLoads() {
    s3.objects.put(CSV_KEY, csv("udprn,wrong").getBytes(StandardCharsets.UTF_8));
    when(loader.stagedManifest(any(), any())).thenReturn(Optional.of(STAGED));
    String event = csvEvent(SCANNED);

    var e = assertThrows(PermanentFailureException.class, () -> service.process(event));

    assertEquals(PermanentFailureException.HEADER_MISMATCH, e.code());
    verify(loader, never()).load(any(), any(), any(), any());
  }

  @Test
  void oversizedHeaderIsAHeaderMismatch() {
    s3.objects.put(CSV_KEY, "x".repeat(70 * 1024).getBytes(StandardCharsets.UTF_8));
    when(loader.stagedManifest(any(), any())).thenReturn(Optional.of(STAGED));
    String event = csvEvent(SCANNED);

    var e = assertThrows(PermanentFailureException.class, () -> service.process(event));

    assertEquals(PermanentFailureException.HEADER_MISMATCH, e.code());
  }

  @Test
  void toleratesBomAndCrlfHeader() throws Exception {
    s3.objects.put(CSV_KEY, ("\uFEFF" + header() + "\r\n1,x\r\n").getBytes(StandardCharsets.UTF_8));
    when(loader.stagedManifest(any(), any())).thenReturn(Optional.of(STAGED));
    when(loader.load(any(), any(), any(), any())).thenReturn(true);

    assertEquals("LOADED", service.process(csvEvent(SCANNED)));
  }

  @Test
  void unsupportedManifestSchemaVersionIsPermanent() {
    when(loader.stagedManifest(any(), any()))
        .thenReturn(
            Optional.of(new StagedManifest(UUID.fromString(ID), SupplyType.FULL, "3.0", ROWS)));
    String event = csvEvent(SCANNED);

    var e = assertThrows(PermanentFailureException.class, () -> service.process(event));

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
    String unexpectedBucketEvent = csvEvent("other-bucket");
    String unexpectedKeyEvent = csvEvent(SCANNED).replace(CSV_KEY, "Scanned/" + ID + "/other.csv");
    assertThrows(InvalidEventException.class, () -> service.process(unexpectedBucketEvent));
    assertThrows(InvalidEventException.class, () -> service.process(unexpectedKeyEvent));
  }

  @Test
  void rejectsUnsupportedEventVersionAndBadIds() {
    String unsupportedVersionEvent =
        csvEvent(SCANNED).replace("\"schemaVersion\":1", "\"schemaVersion\":2");
    String invalidIdEvent = csvEvent(SCANNED).replace(ID, "not-a-uuid");
    String unknownDatasetEvent = csvEvent(SCANNED).replace(DATASET, "unknown_dataset");
    assertThrows(InvalidEventException.class, () -> service.process(unsupportedVersionEvent));
    assertThrows(InvalidEventException.class, () -> service.process(invalidIdEvent));
    assertThrows(InvalidEventException.class, () -> service.process(unknownDatasetEvent));
  }

  @Test
  void supersededCsvVersionIsAcknowledgedWithoutLoadingOrBlocking() {
    s3.versions.put(CSV_KEY, "v2");

    var response = service.upload(batch(sqsMessage("old", csvEvent(SCANNED))));

    assertTrue(response.getBatchItemFailures().isEmpty());
    verify(loader, never()).load(any(), any(), any(), any());
    verify(commands, never()).sendIngestion(any(), any());
  }

  @Test
  void supersededManifestVersionIsSkipped() throws Exception {
    s3.versions.put(MANIFEST_KEY, "v2");

    assertEquals("SKIPPED_SUPERSEDED", service.process(manifestEvent(INGEST)));
    verify(loader, never()).recordManifest(any());
  }

  @Test
  void csvForADatasetMissingFromTheRecordedManifestIsPermanent() {
    when(loader.stagedManifest(any(), any())).thenReturn(Optional.empty());
    when(loader.manifestRecorded(UUID.fromString(ID))).thenReturn(true);
    String event = csvEvent(SCANNED);

    var e = assertThrows(PermanentFailureException.class, () -> service.process(event));

    assertEquals(PermanentFailureException.DATASET_NOT_IN_RELEASE, e.code());
    verify(loader, never()).load(any(), any(), any(), any());
  }

  @Test
  void blockedMarkerInTheOtherBucketStillSkipsTheRelease() throws Exception {
    var client = mock(S3Client.class);
    when(client.listObjectsV2(any(ListObjectsV2Request.class)))
        .thenAnswer(call -> {
          ListObjectsV2Request request = call.getArgument(0);
          var response = ListObjectsV2Response.builder();
          if (INGEST.equals(request.bucket())
              && ReleaseStore.blockedKey(ID).equals(request.prefix())) {
            response.contents(S3Object.builder().key(ReleaseStore.blockedKey(ID)).build());
          }
          return response.build();
        });
    when(client.getObject(any(GetObjectRequest.class)))
        .thenAnswer(call ->
            new ResponseInputStream<>(
                GetObjectResponse.builder().build(),
                AbortableInputStream.create(
                    new ByteArrayInputStream("{}".getBytes(StandardCharsets.UTF_8)))));
    var blocked =
        new UploadService(client, new UploadProperties(SCANNED, INGEST), loader, commands);

    assertEquals("SKIPPED_BLOCKED", blocked.process(csvEvent(SCANNED)));
    verify(loader, never()).load(any(), any(), any(), any());
  }

  @Test
  void manifestsAndCsvsMayShareOneBucket() throws Exception {
    var shared =
        new UploadService(s3.client(), new UploadProperties(INGEST, INGEST), loader, commands);
    when(loader.stagedManifest(any(), any())).thenReturn(Optional.of(STAGED));
    when(loader.load(any(), any(), any(), any())).thenReturn(true);

    assertEquals("MANIFEST_RECORDED", shared.process(manifestEvent(INGEST)));
    assertEquals("LOADED", shared.process(csvEvent(INGEST)));
  }

  @Test
  void permanentFailurePublishesStepRejectedAndAcknowledges() throws Exception {
    s3.objects.put(CSV_KEY, csv("udprn,wrong").getBytes(StandardCharsets.UTF_8));
    when(loader.stagedManifest(any(), any())).thenReturn(Optional.of(STAGED));

    var response = service.upload(batch(sqsMessage("bad", csvEvent(SCANNED))));

    assertTrue(response.getBatchItemFailures().isEmpty());
    verify(commands)
        .sendIngestion(
            eq("StepRejected"),
            argThat((Map<String, ?> p) ->
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

    var response = service.upload(batch(sqsMessage("bad", csvEvent(SCANNED))));

    assertEquals(1, response.getBatchItemFailures().size());
  }

  @Test
  void transientFailuresAreRetriedIndividually() {
    when(loader.stagedManifest(any(), any())).thenReturn(Optional.empty());

    var response = service.upload(batch(sqsMessage("pending", csvEvent(SCANNED))));

    assertEquals(1, response.getBatchItemFailures().size());
    assertEquals("pending", response.getBatchItemFailures().getFirst().getItemIdentifier());
  }

  @Test
  void unexpectedIllegalArgumentFromADependencyIsRetriedNotRejected() throws Exception {
    when(loader.stagedManifest(any(), any()))
        .thenThrow(new IllegalArgumentException("library bug"));

    var response = service.upload(batch(sqsMessage("lib", csvEvent(SCANNED))));

    assertEquals(1, response.getBatchItemFailures().size());
    verify(commands, never()).sendIngestion(any(), any());
  }

  @Test
  void invalidEventWithReleaseIdIsRejectedAndAcknowledged() throws Exception {
    var response = service.upload(batch(sqsMessage("bad", csvEvent("other-bucket"))));

    assertTrue(response.getBatchItemFailures().isEmpty());
    verify(commands)
        .sendIngestion(
            eq("StepRejected"),
            argThat((Map<String, ?> p) ->
                PermanentFailureException.INVALID_EVENT.equals(p.get("code"))
                    && ID.equals(p.get("releaseId"))));
  }

  @Test
  void invalidEventWithoutReleaseIdIsDroppedAndAcknowledged() throws Exception {
    var response =
        service.upload(
            batch(
                sqsMessage("garbage", "not json"),
                sqsMessage("badId", csvEvent(SCANNED).replace(ID, "x"))));

    assertTrue(response.getBatchItemFailures().isEmpty());
    verify(commands, never()).sendIngestion(any(), any());
  }

  private static SQSEvent batch(SQSEvent.SQSMessage... records) {
    var batch = new SQSEvent();
    batch.setRecords(List.of(records));
    return batch;
  }

  private static SQSEvent.SQSMessage sqsMessage(String id, String body) {
    var message = new SQSEvent.SQSMessage();
    message.setMessageId(id);
    message.setBody(body);
    return message;
  }

  private static String csvEvent(String bucket) {
    return """
    {"detail-type":"DatasetReady","detail":{"schemaVersion":1,"releaseId":"%s","dataset":"%s",
    "object":{"bucket":"%s","key":"%s","versionId":"v1"}}}
    """
        .formatted(ID, DATASET, bucket, CSV_KEY);
  }

  private static String manifestEvent(String bucket) {
    return """
    {"detail-type":"ManifestReady","detail":{"schemaVersion":1,"releaseId":"%s",
    "object":{"bucket":"%s","key":"%s","versionId":"v1"}}}
    """
        .formatted(ID, bucket, MANIFEST_KEY);
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
    """
        .formatted(ID, ROWS);
  }
}
