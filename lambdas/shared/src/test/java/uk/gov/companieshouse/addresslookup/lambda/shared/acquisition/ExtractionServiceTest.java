package uk.gov.companieshouse.addresslookup.lambda.shared.acquisition;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.argThat;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.doNothing;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import com.amazonaws.services.lambda.runtime.Context;
import com.fasterxml.jackson.databind.JsonNode;
import org.junit.jupiter.api.Test;
import uk.gov.companieshouse.addresslookup.lambda.shared.storage.ReleaseStore;
import uk.gov.companieshouse.addresslookup.lambda.shared.testsupport.FakeS3;
import uk.gov.companieshouse.addresslookup.lambda.shared.testsupport.TestContext;
import uk.gov.companieshouse.release.model.Dataset;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.function.Consumer;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import software.amazon.awssdk.core.ResponseInputStream;
import software.amazon.awssdk.core.sync.RequestBody;
import software.amazon.awssdk.http.AbortableInputStream;
import software.amazon.awssdk.services.s3.S3Client;
import software.amazon.awssdk.services.s3.model.CompleteMultipartUploadResponse;
import software.amazon.awssdk.services.s3.model.CreateMultipartUploadRequest;
import software.amazon.awssdk.services.s3.model.CreateMultipartUploadResponse;
import software.amazon.awssdk.services.s3.model.GetObjectRequest;
import software.amazon.awssdk.services.s3.model.GetObjectResponse;
import software.amazon.awssdk.services.s3.model.GetObjectTaggingRequest;
import software.amazon.awssdk.services.s3.model.GetObjectTaggingResponse;
import software.amazon.awssdk.services.s3.model.HeadObjectRequest;
import software.amazon.awssdk.services.s3.model.HeadObjectResponse;
import software.amazon.awssdk.services.s3.model.ListObjectsV2Request;
import software.amazon.awssdk.services.s3.model.ListObjectsV2Response;
import software.amazon.awssdk.services.s3.model.PutObjectRequest;
import software.amazon.awssdk.services.s3.model.PutObjectResponse;
import software.amazon.awssdk.services.s3.model.S3Object;
import software.amazon.awssdk.services.s3.model.S3Exception;
import software.amazon.awssdk.services.s3.model.Tag;
import software.amazon.awssdk.services.s3.model.UploadPartResponse;
import uk.gov.companieshouse.addresslookup.lambda.shared.storage.ReleaseStore;
import uk.gov.companieshouse.release.model.DatasetCatalog;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static uk.gov.companieshouse.addresslookup.lambda.shared.runtime.RuntimeSupport.JSON;

final class ExtractionServiceTest {
    private static final String BUCKET = "source-bucket";
    private static final String SCANNED_BUCKET = "scanned-bucket";
    private static final String PLAN_KEY = "acquisitions/2026-05-16/4b845f1a-b0dc-33f1-b76b-b76bb7cd0c56.json";
    private static final String RELEASE_ID = "4b845f1a-b0dc-33f1-b76b-b76bb7cd0c56";
    private static final String DATASET = "add_gb_royalmailaddress";
    private static final String ZIP_KEY = "Scanned/" + RELEASE_ID + "/" + DATASET + ".zip";
    private static final String ID = "12345678-1234-1234-1234-123456789abc";
    private static final String RECEIPT_KEY = ReleaseStore.receiptKey(ID, DATASET, "extract");
    private static final String SOURCE = "source";
    private static final String CHECKED = "checked";
    private static final long PLENTY_OF_TIME = 900_000;

    private record StoredObject(String bucket, String key) {}
    private final FakeS3 s3 = new FakeS3();
  private final Commands commands = mock(Commands.class);
  private final Context context = mock(Context.class);
  private final Map<StoredObject, byte[]> objects = new HashMap<>();
  private byte[] uploaded;
  private boolean failReceiptOnce;
  private boolean failEvidenceOnce;
  private boolean conflictReceiptOnce;
  private boolean conflictingReceiptOnce;
  private final ExtractionService service =
      new ExtractionService(
          s3,
          commands,
          new AcquisitionProperties(
              SOURCE, CHECKED, "events", "key", "packages", 1024 * 1024, 1024 * 1024, 20,
              Duration.ofSeconds(20), Duration.ofSeconds(60)));

  @BeforeEach
  void setUp() throws Exception {
    when(context.getRemainingTimeInMillis()).thenReturn(900_000);
    when(s3.listObjectsV2(any(ListObjectsV2Request.class)))
        .thenAnswer(
            call -> {
              var request = call.getArgument(0, ListObjectsV2Request.class);
              return objects.containsKey(stored(request.bucket(), request.prefix()))
                  ? ListObjectsV2Response.builder()
                      .contents(S3Object.builder().key(request.prefix()).build())
                      .build()
                  : ListObjectsV2Response.builder().build();
            });
    when(s3.getObject(any(GetObjectRequest.class)))
        .thenAnswer(
            call -> {
              var request = call.getArgument(0, GetObjectRequest.class);
              byte[] content = objects.get(stored(request.bucket(), request.key()));
              if (content == null)
                throw new IllegalStateException("Missing fixture: " + request.bucket() + "/" + request.key());
              return new ResponseInputStream<>(
                  GetObjectResponse.builder().build(),
                  AbortableInputStream.create(new ByteArrayInputStream(content)));
            });
    when(s3.putObject(any(PutObjectRequest.class), any(RequestBody.class)))
        .thenAnswer(
            call -> recordObject(
                call.getArgument(0, PutObjectRequest.class),
                call.getArgument(1, RequestBody.class)));
    when(s3.putObject(any(Consumer.class), any(RequestBody.class)))
        .thenAnswer(
            call -> {
              var builder = PutObjectRequest.builder();
              Consumer<PutObjectRequest.Builder> request = call.getArgument(0);
              request.accept(builder);
              return recordObject(builder.build(), call.getArgument(1, RequestBody.class));
            });
  }

    // Download Tests
    @Test
    void extractsPrimaryCsvAndRecordsReceipt() throws Exception {
        String csv = header() + "\nrow-1\nrow-2\n";
        stageCleanZip(zip(entries(DATASET + ".csv", csv, DATASET + "_extra.csv", "a,b\n1,2\n")));

        assertEquals("CSV_SCAN", service(1024 * 1024).unzip(event(), context(PLENTY_OF_TIME)));

        assertEquals(1, s3.completedKeys.size());
        String csvKey = s3.completedKeys.get(0);
        assertTrue(csvKey.startsWith("quarantine/csv/" + RELEASE_ID + "/" + DATASET + "/"));
        assertTrue(csvKey.endsWith(".csv"));
        assertEquals(csv, new String(s3.objects.get(csvKey), StandardCharsets.UTF_8));
        var receipt = JSON.readTree(s3.objects.get(ReleaseStore.receiptKey(RELEASE_ID, DATASET, "extract")));
        assertEquals(csvKey, receipt.path("key").asText());
    }

    @Test
    void usesTheVersionStoredInTheDownloadReceiptWhenPresent() throws Exception {
        stageCleanZip(zip(entries(DATASET + ".csv", header() + "\nrow\n")), JSON.writeValueAsBytes(
                Map.of("s3Key", ZIP_KEY, "version", FakeS3.DEFAULT_VERSION)));

        assertEquals("CSV_SCAN", service(1024 * 1024).unzip(event(), context(PLENTY_OF_TIME)));
    }

    @Test
    void skipsWhenExtractReceiptAlreadyExists() throws Exception {
        s3.objects.put(PLAN_KEY, plan());
        s3.objects.put(ReleaseStore.receiptKey(RELEASE_ID, DATASET, "extract"), "{}".getBytes(StandardCharsets.UTF_8));

        assertEquals("SKIPPED", service(1024).unzip(event(), context(PLENTY_OF_TIME)));
        assertTrue(s3.completedKeys.isEmpty());
    }

    @Test
    void waitsWhenNoDownloadReceiptExists() throws Exception {
        s3.objects.put(PLAN_KEY, plan());

        assertEquals("WAITING_FOR_ZIP_SCAN", service(1024).unzip(event(), context(PLENTY_OF_TIME)));
    }

    @Test
    void waitsWhileZipScanIsPending() throws Exception {
        stageCleanZip(zip(entries(DATASET + ".csv", header() + "\n")));
        s3.scanStatus.clear();

        assertEquals("WAITING_FOR_ZIP_SCAN", service(1024).unzip(event(), context(PLENTY_OF_TIME)));
        assertTrue(s3.completedKeys.isEmpty());
    }

    @Test
    void rejectsMissingPlan() {
        var failure = assertUnzipThrows(IllegalArgumentException.class, 1024, PLENTY_OF_TIME);

        assertEquals("Missing acquisition plan", failure.getMessage());
    }

    @Test
    void rejectsPlanKeyOutsideAcquisitionsPrefix() {
        var event = Map.<String, Object>of("detail", Map.of("planKey", "other/plan.json", "dataset", DATASET));

        var service = service(1024);
        var context = context(PLENTY_OF_TIME);

        var failure = assertThrows(IllegalArgumentException.class, () -> service.unzip(event, context));

        assertEquals("Invalid plan key", failure.getMessage());
    }

    @Test
    void rejectsCsvWhoseHeaderDoesNotMatchTheCatalogue() throws Exception {
        stageCleanZip(zip(entries(DATASET + ".csv", "not,the,right,header\nrow\n")));

        var failure = assertUnzipThrows(IllegalArgumentException.class, 1024 * 1024, PLENTY_OF_TIME);

        assertEquals("CSV header does not match pinned OS DDL", failure.getMessage());
        assertTrue(s3.completedKeys.isEmpty());
    }

    @Test
    void rejectsZipWithoutPrimaryCsv() throws Exception {
        stageCleanZip(zip(entries(DATASET + "_extra.csv", "a,b\n")));

        var failure = assertUnzipThrows(IllegalArgumentException.class, 1024 * 1024, PLENTY_OF_TIME);

        assertEquals("Missing primary CSV", failure.getMessage());
    }

    @Test
    void rejectsUnexpectedZipMembers() throws Exception {
        stageCleanZip(zip(entries("something_else.csv", "x\n")));

        var failure = assertUnzipThrows(IllegalArgumentException.class, 1024 * 1024, PLENTY_OF_TIME);

        assertEquals("Unexpected ZIP member", failure.getMessage());
    }

    @Test
    void rejectsZipMembersWithPaths() throws Exception {
        for (String name : new String[]{"nested/" + DATASET + ".csv", "..", "dir\\file.csv"}) {
            s3.objects.clear();
            stageCleanZip(zip(entries(name, "x\n")));

            var failure = assertUnzipThrows(IllegalArgumentException.class, 1024 * 1024, PLENTY_OF_TIME);

            assertEquals("Unexpected ZIP member path", failure.getMessage(), name);
        }
    }

    @Test
    void rejectsAncillaryEntriesThatExceedTheExpandedSizeLimit() throws Exception {
        stageCleanZip(zip(entries(DATASET + "_big.csv", "x".repeat(200))));

        var failure = assertUnzipThrows(IllegalArgumentException.class, 100, PLENTY_OF_TIME);

        assertEquals("ZIP expanded size limit", failure.getMessage());
    }

    @Test
    void rejectsPrimaryCsvThatExceedsTheExpandedSizeLimit() throws Exception {
        stageCleanZip(zip(entries(DATASET + ".csv", header() + "\n" + "row\n".repeat(500))));

        var failure = assertUnzipThrows(IOException.class, 300, PLENTY_OF_TIME);

        assertEquals("Expanded archive limit", failure.getMessage());
        assertTrue(s3.completedKeys.isEmpty());
        assertTrue(s3.abortedUploads > 0);
    }

    @Test
    void failsWhenTheDeadlineIsApproachingWhileSkippingAncillaryEntries() throws Exception {
        stageCleanZip(zip(entries(DATASET + "_extra.csv", "a,b\n")));

        var failure = assertUnzipThrows(IllegalArgumentException.class, 1024 * 1024, 1_000);

        assertEquals("Unzip deadline", failure.getMessage());
    }

    @Test
    void constructorRequiresScannedBucket() {
        var properties = new AcquisitionProperties(
                BUCKET, " ", "bus", "key", "{}", 1024, 1024, 20, Duration.ofSeconds(1), Duration.ofSeconds(1));

        var client = s3.client();

        var failure = assertThrows(IllegalArgumentException.class, () -> new ExtractionService(client, properties));

        assertEquals("Missing SCANNED_BUCKET", failure.getMessage());
    }

    @Test
    void countingInputCountsSingleByteAndBulkReads() throws Exception {
        var single = new ExtractionService.CountingInput(new ByteArrayInputStream(new byte[]{1, 2, 3}), 10);
        assertEquals(1, single.read());
        assertEquals(2, single.read());
        assertEquals(2, single.count);

        var bulk = new ExtractionService.CountingInput(new ByteArrayInputStream(new byte[4]), 10);
        assertEquals(4, bulk.read(new byte[8], 0, 8));
        assertEquals(-1, bulk.read(new byte[8], 0, 8));
        assertEquals(4, bulk.count);
    }

    @Test
    void countingInputFailsOnceTheLimitIsExceeded() throws Exception {
        var in = new ExtractionService.CountingInput(new ByteArrayInputStream(new byte[]{1, 2, 3}), 2);

        assertEquals(1, in.read());
        assertEquals(2, in.read());
        var failure = assertThrows(IOException.class, in::read);

        assertEquals("Expanded archive limit", failure.getMessage());
    }

    // Builds everything first so the asserted lambda contains a single call that can fail.
    private <T extends Throwable> T assertUnzipThrows(Class<T> expected, long maxExtractedBytes, long remainingMillis) {
        var service = service(maxExtractedBytes);
        var event = event();
        var context = context(remainingMillis);

        return assertThrows(expected, () -> service.unzip(event, context));
    }

    // Unzip Tests

    @Test
    void rejectsMissingSourceZip() throws Exception {
        when(s3.getObjectTagging(any(GetObjectTaggingRequest.class)))
                .thenThrow(S3Exception.builder().statusCode(404).build());

        assertThrows(S3Exception.class, () -> service.unzip(event(), context));
        verify(s3, never()).getObject(argThat((GetObjectRequest request) -> ZIP_KEY.equals(request.key())));
        verifyNoInteractions(commands);
    }

    @Test
    void waitsForPendingZipScan() throws Exception {
        objects.put(stored(SOURCE, ZIP_KEY), validZip());
        when(s3.getObjectTagging(any(GetObjectTaggingRequest.class)))
                .thenReturn(GetObjectTaggingResponse.builder().build());

        assertEquals("WAITING_FOR_ZIP_SCAN", service.unzip(event(), context));
        verify(s3, never()).getObject(argThat((GetObjectRequest request) -> ZIP_KEY.equals(request.key())));
        verifyNoInteractions(commands);
    }

    @Test
    void rejectsMismatchedHeaderWithoutUploading() throws Exception {
        cleanZip(zip(Map.of(DATASET + ".csv", "wrong,header\n1,2\n")));

        assertEquals("HEADER_MISMATCH", service.unzip(event(), context));
        verify(s3, never()).createMultipartUpload(any(Consumer.class));
        verifyNoInteractions(commands);
    }

    @Test
    void rejectsUnexpectedCsvFilenameWithoutUploading() throws Exception {
        cleanZip(zip(Map.of("unexpected.csv", "data\n")));

        assertEquals("ARCHIVE_INVALID", service.unzip(event(), context));
        verify(s3, never()).createMultipartUpload(any(Consumer.class));
    }

    @Test
    void reportsMissingPrimaryCsv() throws Exception {
        cleanZip(zip(Map.of(DATASET + "_multires.csv", "data\n")));

        assertEquals("CSV_NOT_FOUND", service.unzip(event(), context));
        verify(s3, never()).createMultipartUpload(any(Consumer.class));
    }

    @Test
    void reportsMalformedZip() throws Exception {
        byte[] malformed = zip(Map.of(DATASET + "_multires.csv", "data\n"));
        malformed[8] = 99;
        cleanZip(malformed);

        assertEquals("MALFORMED_ZIP", service.unzip(event(), context));
        verify(s3, never()).createMultipartUpload(any(Consumer.class));
    }

    @Test
    void rejectsMismatchedZipVersion() throws Exception {
        cleanZip(validZip());
        when(s3.headObject(any(HeadObjectRequest.class)))
                .thenReturn(HeadObjectResponse.builder().versionId("replacement-version").build());

        assertThrows(IllegalArgumentException.class, () -> service.unzip(event(), context));
        verify(s3, never()).createMultipartUpload(any(Consumer.class));
        verifyNoInteractions(commands);
    }

    @Test
    void rejectsUnsupportedExtractRequestVersion() throws Exception {
        cleanZip(validZip());

        assertThrows(IllegalArgumentException.class, () -> service.unzip(event(2), context));
        verify(s3, never()).createMultipartUpload(any(Consumer.class));
        verifyNoInteractions(commands);
    }

    @Test
    void recordsUploadedCsvVersionAndPublishesNextCommand() throws Exception {
        cleanZip(validZip());
        stubUpload();

        assertEquals("CSV_SCAN", service.unzip(event(), context));
        JsonNode receipt = capturedReceipt();
        assertEquals(ID, receipt.path("releaseId").asText());
        assertEquals(DATASET, receipt.path("dataset").asText());
        assertEquals("Scanned/" + ID + "/" + DATASET + ".csv", receipt.path("key").asText());
        assertEquals("csv-version", receipt.path("version").asText());
        assertTrue(receipt.path("sha256").asText().matches("[a-f0-9]{64}"));
        assertEquals("zip-version", receipt.path("zipVersion").asText());
        assertEquals(csvKey(), receipt.path("key").asText());
        verify(s3).getObjectTagging(argThat((GetObjectTaggingRequest request) ->
                SOURCE.equals(request.bucket()) && ZIP_KEY.equals(request.key())
                        && "zip-version".equals(request.versionId())));
        verify(s3).getObject(argThat((GetObjectRequest request) ->
                SOURCE.equals(request.bucket()) && ZIP_KEY.equals(request.key())
                        && "zip-version".equals(request.versionId())));
        verify(commands).send("CSV_SCAN", DATASET);
    }

    @Test
    void eventFailureAfterExtractReceiptIsWritten() throws Exception {
        cleanZip(validZip());
        stubUpload();
        doThrow(new IllegalStateException("EventBridge unavailable"))
                .when(commands).send("CSV_SCAN", DATASET);
        assertThrows(IllegalStateException.class, () -> service.unzip(event(), context));
        JsonNode receipt = capturedReceipt();
        assertEquals("EXTRACTED", receipt.path("status").asText());
        verify(commands).send("CSV_SCAN", DATASET);
    }

    @Test
    void repeatedCallRetriesEvent() throws Exception {
        cleanZip(validZip());
        stubUpload();

        assertEquals("CSV_SCAN", service.unzip(event(), context));
        assertEquals("CSV_SCAN", service.unzip(event(), context));

        verify(s3, times(1)).createMultipartUpload(any(Consumer.class));
        verify(commands, times(2)).send("CSV_SCAN", DATASET);
        assertEquals(csvKey(), capturedReceipt().path("key").asText());
    }

    @Test
    void retriesFailedEventWithoutExtractingAgain() throws Exception {
        cleanZip(validZip());
        stubUpload();
        doThrow(new IllegalStateException("EventBridge unavailable"))
                .when(commands).send("CSV_SCAN", DATASET);

        assertThrows(IllegalStateException.class, () -> service.unzip(event(), context));
        doNothing().when(commands).send("CSV_SCAN", DATASET);
        assertEquals("CSV_SCAN", service.unzip(event(), context));

        verify(s3, times(1)).createMultipartUpload(any(Consumer.class));
        verify(commands, times(2)).send("CSV_SCAN", DATASET);
    }

    @Test
    void recoversFromFailedReceipt() throws Exception {
        cleanZip(validZip());
        stubUpload();
        failReceiptOnce = true;

        assertThrows(S3Exception.class, () -> service.unzip(event(), context));
        assertTrue(objects.containsKey(stored(CHECKED, csvKey())));
        assertEquals("CSV_SCAN", service.unzip(event(), context));

        verify(s3, times(2)).createMultipartUpload(any(Consumer.class));
        verify(commands).send("CSV_SCAN", DATASET);
        assertEquals("csv-version", capturedReceipt().path("version").asText());
    }

    @Test
    void recoversFromFailedChecksum() throws Exception {
        cleanZip(validZip());
        stubUpload();
        failEvidenceOnce = true;

        assertThrows(S3Exception.class, () -> service.unzip(event(), context));
        assertTrue(objects.containsKey(stored(CHECKED, csvKey())));
        assertEquals("CSV_SCAN", service.unzip(event(), context));

        assertTrue(objects.containsKey(stored(CHECKED,
                uk.gov.companieshouse.addresslookup.lambda.shared.storage
                        .StreamingS3.checksumKey(csvKey()))));
        verify(commands).send("CSV_SCAN", DATASET);
    }

    @Test
    void acceptsMatchingReceipt() throws Exception {
        cleanZip(validZip());
        stubUpload();
        conflictReceiptOnce = true;

        assertEquals("CSV_SCAN", service.unzip(event(), context));

        verify(commands).send("CSV_SCAN", DATASET);
        assertEquals("csv-version", capturedReceipt().path("version").asText());
    }

    @Test
    void rejectsConflictingReceipt() throws Exception {
        cleanZip(validZip());
        stubUpload();
        conflictingReceiptOnce = true;

        assertThrows(IllegalArgumentException.class, () -> service.unzip(event(), context));

        verifyNoInteractions(commands);
    }

    @Test
    void rejectsCsvWithDifferentContent() throws Exception {
        cleanZip(validZip());
        stubUpload();
        objects.put(stored(CHECKED, csvKey()), "different CSV".getBytes(StandardCharsets.UTF_8));

        assertThrows(IllegalArgumentException.class, () -> service.unzip(event(), context));
        verifyNoInteractions(commands);
    }

    @Test
    void rejectsReceiptForDifferentZipVersion() throws Exception {
        cleanZip(validZip());
        objects.put(
                stored(SOURCE, RECEIPT_KEY),
                ("{\"releaseId\":\"" + ID + "\",\"dataset\":\"" + DATASET
                        + "\",\"zipS3Key\":\"" + ZIP_KEY + "\",\"zipVersion\":\"other-version\","
                        + "\"key\":\"" + csvKey()
                        + "\",\"status\":\"EXTRACTED\",\"version\":\"csv-version\",\"sha256\":\"abc\"}")
                        .getBytes(StandardCharsets.UTF_8));

        assertThrows(IllegalArgumentException.class, () -> service.unzip(event(), context));
        verify(s3, never()).createMultipartUpload(any(Consumer.class));
        verifyNoInteractions(commands);
    }

    // Helper methods
    private ExtractionService service(long maxExtractedBytes) {
        return new ExtractionService(
                s3.client(),
                new AcquisitionProperties(
                        BUCKET,
                        SCANNED_BUCKET,
                        "event-bus",
                        "redacted",
                        "{}",
                        512L * 1024L * 1024L,
                        maxExtractedBytes,
                        20,
                        Duration.ofSeconds(20),
                        Duration.ofSeconds(840)));
    }

    private void stageCleanZip(byte[] zip) throws Exception {
        stageCleanZip(zip, JSON.writeValueAsBytes(Map.of("s3Key", ZIP_KEY)));
    }

    private void stageCleanZip(byte[] zip, byte[] downloadReceipt) throws Exception {
        s3.objects.put(PLAN_KEY, plan());
        s3.objects.put(ReleaseStore.receiptKey(RELEASE_ID, DATASET, "download"), downloadReceipt);
        s3.objects.put(ZIP_KEY, zip);
        s3.scanStatus.put(ZIP_KEY, ReleaseStore.CLEAN);
    }

    private static Map<String, Object> event() {
        return Map.of("detail", Map.of("planKey", PLAN_KEY, "dataset", DATASET));
    }

    private static Context context(long remainingMillis) {
        return TestContext.withRemainingMillis(remainingMillis);
    }

    private static String header() throws Exception {
        return Dataset.ADD_GB_ROYALMAILADDRESS.table().header();
    }

    private static Map<String, String> entries(String... namesAndContents) {
        var map = new LinkedHashMap<String, String>();
        for (int i = 0; i < namesAndContents.length; i += 2) map.put(namesAndContents[i], namesAndContents[i + 1]);
        return map;
    }

    private void cleanZip(byte[] content) throws Exception {
        objects.put(stored(SOURCE, ZIP_KEY), content);
        when(s3.getObjectTagging(any(GetObjectTaggingRequest.class)))
                .thenReturn(
                        GetObjectTaggingResponse.builder()
                                .tagSet(Tag.builder().key("GuardDutyMalwareScanStatus").value("NO_THREATS_FOUND").build())
                                .build());
        when(s3.headObject(any(HeadObjectRequest.class)))
                .thenAnswer(
                        call -> HeadObjectResponse.builder()
                                .versionId(CHECKED.equals(call.getArgument(0, HeadObjectRequest.class).bucket())
                                        ? "csv-version" : "zip-version")
                                .build());
    }

    private byte[] validZip() throws Exception {
        String header = DatasetCatalog.tables().stream()
                .filter(t -> DATASET.equals(t.name()))
                .findFirst()
                .orElseThrow()
                .header();
        return zip(Map.of(DATASET + ".csv", header + "\n"));
    }

    private static byte[] zip(Map<String, String> entries) throws IOException {
        var out = new ByteArrayOutputStream();
        try (var zip = new ZipOutputStream(out)) {
            for (var entry : entries.entrySet()) {
                zip.putNextEntry(new ZipEntry(entry.getKey()));
                zip.write(entry.getValue().getBytes(StandardCharsets.UTF_8));
                zip.closeEntry();
            }
        }
        return out.toByteArray();
    }

    private static byte[] plan() throws Exception {
        try (var in = ExtractionServiceTest.class.getResourceAsStream(
                "/example-download-lambda/4b845f1a-b0dc-33f1-b76b-b76bb7cd0c56.json")) {
            assertNotNull(in, "Missing plan fixture");
            return in.readAllBytes();
        }
    }
}

private void stubUpload() throws Exception {
    when(s3.createMultipartUpload(any(Consumer.class)))
            .thenAnswer(
                    call -> {
                        Consumer<CreateMultipartUploadRequest.Builder> request = call.getArgument(0);
                        var builder = CreateMultipartUploadRequest.builder();
                        request.accept(builder);
                        assertEquals(CHECKED, builder.build().bucket());
                        assertEquals(csvKey(), builder.build().key());
                        return CreateMultipartUploadResponse.builder().uploadId("upload-id").build();
                    });
    when(s3.uploadPart(any(Consumer.class), any(RequestBody.class)))
            .thenAnswer(
                    call -> {
                        try (var in = call.getArgument(1, RequestBody.class)
                                .contentStreamProvider().newStream()) {
                            uploaded = in.readAllBytes();
                        }
                        return UploadPartResponse.builder().eTag("etag").build();
                    });
    when(s3.completeMultipartUpload(any(Consumer.class)))
            .thenAnswer(
                    call -> {
                        if (objects.containsKey(stored(CHECKED, csvKey())))
                            throw S3Exception.builder().statusCode(412).build();
                        objects.put(stored(CHECKED, csvKey()), uploaded);
                        return CompleteMultipartUploadResponse.builder().versionId("csv-version").build();
                    });
}

private JsonNode capturedReceipt() throws Exception {
    assertTrue(objects.containsKey(stored(SOURCE, RECEIPT_KEY)));
    return uk.gov.companieshouse.addresslookup.lambda.shared.runtime.RuntimeSupport.JSON
            .readTree(objects.get(stored(SOURCE, RECEIPT_KEY)));
}

private PutObjectResponse recordObject(PutObjectRequest request, RequestBody body) throws Exception {
    if (failReceiptOnce && RECEIPT_KEY.equals(request.key())) {
        failReceiptOnce = false;
        throw S3Exception.builder().statusCode(503).message("S3 unavailable").build();
    }
    if (failEvidenceOnce && request.key().startsWith("metadata/checksums/")) {
        failEvidenceOnce = false;
        throw S3Exception.builder().statusCode(503).message("S3 unavailable").build();
    }
    if (objects.containsKey(stored(request.bucket(), request.key())))
        throw S3Exception.builder().statusCode(412).build();
    try (var in = body.contentStreamProvider().newStream()) {
        byte[] contents = in.readAllBytes();
        if (conflictingReceiptOnce && RECEIPT_KEY.equals(request.key())) {
            conflictingReceiptOnce = false;
            var conflicting = uk.gov.companieshouse.addresslookup.lambda.shared.runtime.RuntimeSupport.JSON
                    .readTree(contents).deepCopy();
            ((com.fasterxml.jackson.databind.node.ObjectNode) conflicting).put("zipVersion", "other-version");
            objects.put(stored(request.bucket(), request.key()),
                    conflicting.toString().getBytes(StandardCharsets.UTF_8));
            throw S3Exception.builder().statusCode(412).build();
        }
        objects.put(stored(request.bucket(), request.key()), contents);
    }
    if (conflictReceiptOnce && RECEIPT_KEY.equals(request.key())) {
        conflictReceiptOnce = false;
        throw S3Exception.builder().statusCode(412).build();
    }
    return PutObjectResponse.builder().build();
}

private static String csvKey() {
    return ReleaseStore.extractedCsvKey(ID, DATASET);
}

private static StoredObject stored(String bucket, String key) {
    return new StoredObject(bucket, key);
}

private static Map<String, Object> event() {
    return event(1);
}

  private static Map<String, Object> event(int schemaVersion) {
    return Map.of("detail", Map.of(
        "schemaVersion", schemaVersion,
        "releaseId", ID,
        "dataset", DATASET,
        "object", Map.of("bucket", SOURCE, "key", ZIP_KEY, "versionId", "zip-version")));
  }
}
