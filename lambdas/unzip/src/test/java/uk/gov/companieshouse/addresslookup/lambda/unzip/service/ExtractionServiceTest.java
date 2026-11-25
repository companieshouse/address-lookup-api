package uk.gov.companieshouse.addresslookup.lambda.unzip.service;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.argThat;
import static org.mockito.Mockito.doNothing;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.LoggerContext;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;

import com.amazonaws.services.lambda.runtime.Context;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.slf4j.LoggerFactory;

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
import software.amazon.awssdk.services.s3.model.S3Exception;
import software.amazon.awssdk.services.s3.model.S3Object;
import software.amazon.awssdk.services.s3.model.Tag;
import software.amazon.awssdk.services.s3.model.UploadPartResponse;

import uk.gov.companieshouse.addresslookup.lambda.shared.acquisition.AcquisitionProperties;
import uk.gov.companieshouse.addresslookup.lambda.shared.acquisition.Commands;
import uk.gov.companieshouse.addresslookup.lambda.shared.exception.PermanentFailureException;
import uk.gov.companieshouse.addresslookup.lambda.shared.util.KeyLayout;
import uk.gov.companieshouse.logging.StructuredLogger;
import uk.gov.companieshouse.release.model.Dataset;
import uk.gov.companieshouse.release.model.DatasetCatalog;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.HashMap;
import java.util.Map;
import java.util.function.Consumer;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;

@ExtendWith(MockitoExtension.class)
final class ExtractionServiceTest {
    private static final Logger STRUCTURED_LOGGER =
            (Logger) LoggerFactory.getLogger(StructuredLogger.class);
    private static final String DATASET = "add_gb_royalmailaddress";
    private static final String ID = "12345678-1234-1234-1234-123456789abc";
    private static final String ZIP_KEY = "landing/daily_cou/20780/150104/downloads/" + DATASET + ".zip";
    private static final String SOURCE = "landing";
    private static final String CHECKED = SOURCE;

    private record StoredObject(String bucket, String key) {}

    @Mock private S3Client s3;
    @Mock private Commands commands;
    @Mock private Context context;
    private final Map<StoredObject, byte[]> objects = new HashMap<>();
    private byte[] uploaded;
    private boolean failEvidenceOnce;
    private ExtractionService service;

    @BeforeEach
    void setUp() throws Exception {
        service =
                new ExtractionService(
                        s3,
                        commands,
                        new AcquisitionProperties(
                                "control",
                                SOURCE,
                                "events",
                                "key",
                                "packages",
                                1024 * 1024,
                                null,
                                null,
                                1024 * 1024,
                                20,
                                Duration.ofSeconds(20),
                                Duration.ofSeconds(60)));

        lenient().when(context.getRemainingTimeInMillis()).thenReturn(900_000);
        lenient()
                .when(s3.listObjectsV2(any(ListObjectsV2Request.class)))
                .thenAnswer(
                        call -> {
                            var request = call.getArgument(0, ListObjectsV2Request.class);
                            return objects.containsKey(stored(request.bucket(), request.prefix()))
                                    ? ListObjectsV2Response.builder()
                                            .contents(
                                                    S3Object.builder()
                                                            .key(request.prefix())
                                                            .build())
                                            .build()
                                    : ListObjectsV2Response.builder().build();
                        });
        lenient()
                .when(s3.getObject(any(GetObjectRequest.class)))
                .thenAnswer(
                        call -> {
                            var request = call.getArgument(0, GetObjectRequest.class);
                            byte[] content = objects.get(stored(request.bucket(), request.key()));
                            if (content == null)
                                throw new IllegalStateException(
                                        "Missing fixture: "
                                                + request.bucket()
                                                + "/"
                                                + request.key());
                            return new ResponseInputStream<>(
                                    GetObjectResponse.builder().eTag("fixture-etag").build(),
                                    AbortableInputStream.create(new ByteArrayInputStream(content)));
                        });
        lenient()
                .when(s3.putObject(any(PutObjectRequest.class), any(RequestBody.class)))
                .thenAnswer(
                        call ->
                                recordObject(
                                        call.getArgument(0, PutObjectRequest.class),
                                        call.getArgument(1, RequestBody.class)));
        lenient()
                .when(s3.putObject(any(Consumer.class), any(RequestBody.class)))
                .thenAnswer(
                        call -> {
                            var builder = PutObjectRequest.builder();
                            Consumer<PutObjectRequest.Builder> request = call.getArgument(0);
                            request.accept(builder);
                            return recordObject(
                                    builder.build(), call.getArgument(1, RequestBody.class));
                        });
    }

    @Test
    void rejectsMissingSourceZip() throws Exception {
        when(s3.getObjectTagging(any(GetObjectTaggingRequest.class)))
                .thenThrow(S3Exception.builder().statusCode(404).build());
        var unzipEvent = event();

        assertThrows(S3Exception.class, () -> service.unzip(unzipEvent, context));
        verify(s3, never())
                .getObject(argThat((GetObjectRequest request) -> ZIP_KEY.equals(request.key())));
        verifyNoInteractions(commands);
    }

    @Test
    void waitsForPendingZipScan() throws Exception {
        objects.put(stored(SOURCE, ZIP_KEY), validZip());
        when(context.getAwsRequestId()).thenReturn("request-id");
        when(s3.getObjectTagging(any(GetObjectTaggingRequest.class)))
                .thenReturn(GetObjectTaggingResponse.builder().build());

        assertEquals("WAITING_FOR_ZIP_SCAN", service.unzip(event(), context));
        verify(s3, never())
                .getObject(argThat((GetObjectRequest request) -> ZIP_KEY.equals(request.key())));
        verifyNoInteractions(commands);
    }

    @Test
    void rejectsMismatchedHeaderWithoutUploading() throws Exception {
        cleanZip(zip(Map.of(DATASET + ".csv", "wrong,header\n1,2\n")));

        assertPermanentFailure(service, "HEADER_MISMATCH");
        verify(s3, never()).createMultipartUpload(any(Consumer.class));
        verifyNoInteractions(commands);
    }

    @Test
    void rejectsUnexpectedCsvFilenameWithoutUploading() throws Exception {
        cleanZip(zip(Map.of("unexpected.csv", "data\n")));

        assertPermanentFailure(service, "ARCHIVE_INVALID");
        verify(s3, never()).createMultipartUpload(any(Consumer.class));
    }

    @Test
    void rejectsZipMembersWithPaths() throws Exception {
        for (String name : new String[] {"nested/" + DATASET + ".csv", "..", "dir\\file.csv"}) {
            cleanZip(zip(Map.of(name, "data\n")));

            assertPermanentFailure(service, "ARCHIVE_INVALID");
        }
        verify(s3, never()).createMultipartUpload(any(Consumer.class));
    }

    @Test
    void rejectsAncillaryEntriesThatExceedTheExpandedSizeLimit() throws Exception {
        cleanZip(zip(Map.of(DATASET + "_big.csv", "x".repeat(200))));

        assertPermanentFailure(serviceWithLimit(100), "MALFORMED_ZIP");
        verify(s3, never()).createMultipartUpload(any(Consumer.class));
    }

    @Test
    void rejectsPrimaryCsvThatExceedsTheExpandedSizeLimit() throws Exception {
        cleanZip(zip(Map.of(DATASET + ".csv", header() + "\n" + "row\n".repeat(100))));
        stubUpload();

        assertPermanentFailure(serviceWithLimit(100), "MALFORMED_ZIP");
        verify(s3, never()).completeMultipartUpload(any(Consumer.class));
        verify(s3).abortMultipartUpload(any(Consumer.class));
    }

    @Test
    void failsWhenDeadlineApproachesWhileSkippingAncillaryEntries() throws Exception {
        cleanZip(zip(Map.of(DATASET + "_extra.csv", "a,b\n")));
        when(context.getRemainingTimeInMillis()).thenReturn(1_000);
        var request = event();

        var failure =
                assertThrows(IllegalArgumentException.class, () -> service.unzip(request, context));

        assertEquals("Unzip deadline", failure.getMessage());
    }

    @Test
    void constructorRequiresLandingBucket() {
        var properties =
                new AcquisitionProperties(
                        "control",
                        " ",
                        "events",
                        "key",
                        "packages",
                        1024,
                        null,
                        null,
                        1024,
                        20,
                        Duration.ofSeconds(20),
                        Duration.ofSeconds(60));

        var failure =
                assertThrows(
                        IllegalArgumentException.class,
                        () -> new ExtractionService(s3, commands, properties));

        assertEquals("Missing LANDING_BUCKET", failure.getMessage());
    }

    @Test
    void reportsMissingPrimaryCsv() throws Exception {
        cleanZip(zip(Map.of(DATASET + "_multires.csv", "data\n")));

        assertPermanentFailure(service, "CSV_NOT_FOUND");
        verify(s3, never()).createMultipartUpload(any(Consumer.class));
    }

    @Test
    void reportsMalformedZip() throws Exception {
        byte[] malformed = zip(Map.of(DATASET + "_multires.csv", "data\n"));
        malformed[8] = 99;
        cleanZip(malformed);

        assertPermanentFailure(service, "MALFORMED_ZIP");
        verify(s3, never()).createMultipartUpload(any(Consumer.class));
    }

    @Test
    void permanentFailureLogsReleaseIdDatasetAndReasonCode() throws Exception {
        cleanZip(zip(Map.of(DATASET + "_multires.csv", "data\n")));
        var appender = captureStructuredLogs();
        try {
            assertPermanentFailure(service, "CSV_NOT_FOUND");
        } finally {
            stopCapturingStructuredLogs(appender);
        }

        var failureLog =
                appender.list.stream()
                        .filter(
                                event ->
                                        event.getFormattedMessage().equals("ZIP extraction failed"))
                        .findFirst()
                        .orElseThrow();
        assertEquals("PERMANENT_FAILURE", structuredField(failureLog, "outcome"));
        assertEquals("CSV_NOT_FOUND", structuredField(failureLog, "reason_code"));
        assertEquals("20780-150104", structuredField(failureLog, "release_id"));
        assertEquals(DATASET, structuredField(failureLog, "dataset"));
        assertEquals(ZIP_KEY, structuredField(failureLog, "s3_key"));
    }

    @Test
    void repeatedPermanentFailureHasNoSideEffects() throws Exception {
        cleanZip(zip(Map.of(DATASET + ".csv", "wrong,header\n1,2\n")));

        assertPermanentFailure(service, "HEADER_MISMATCH");
        assertPermanentFailure(service, "HEADER_MISMATCH");

        verify(s3, never()).createMultipartUpload(any(Consumer.class));
        assertTrue(objects.keySet().stream().noneMatch(key -> key.key().endsWith(".receipt.json")));
        verifyNoInteractions(commands);
    }

    @Test
    void rejectsMismatchedZipVersion() throws Exception {
        cleanZip(validZip());
        when(s3.headObject(any(HeadObjectRequest.class)))
                .thenReturn(HeadObjectResponse.builder().versionId("replacement-version").build());
        var request = event();

        assertThrows(IllegalArgumentException.class, () -> service.unzip(request, context));
        verify(s3, never()).createMultipartUpload(any(Consumer.class));
        verifyNoInteractions(commands);
    }

    @Test
    void rejectsUnsupportedExtractRequestVersion() throws Exception {
        cleanZip(validZip());
        var request = event(2);

        assertThrows(IllegalArgumentException.class, () -> service.unzip(request, context));
        verify(s3, never()).createMultipartUpload(any(Consumer.class));
        verifyNoInteractions(commands);
    }

    @Test
    void extractsPrimaryCsvAfterAncillaryEntry() throws Exception {
        String csv = header() + "\nrow-1\nrow-2\n";
        var entries = new java.util.LinkedHashMap<String, String>();
        entries.put(DATASET + "_extra.csv", "a,b\n1,2\n");
        entries.put(DATASET + ".csv", csv);
        cleanZip(zip(entries));
        stubUpload();

        assertEquals("CSV_SCAN", service.unzip(event(), context));
        assertEquals(csv, new String(uploaded, StandardCharsets.UTF_8));
        assertTrue(objects.containsKey(stored(CHECKED, csvKey())));
        var parsedCsv = KeyLayout.parseLandingObject(csvKey());
        assertEquals(KeyLayout.LandingFileType.CSV, parsedCsv.fileType());
        assertEquals("20780", parsedCsv.packageVersion().packageId());
        assertEquals("150104", parsedCsv.packageVersion().versionId());
    }

    @Test
    void acceptsDownloadOutputAndProducesImportLayoutCsv() throws Exception {
        var packageVersion =
                new KeyLayout.PackageVersion(KeyLayout.Feed.DAILY_COU, "20780", "150104");
        String downloadOutputKey = KeyLayout.downloadZipKey(packageVersion, Dataset.named(DATASET));
        assertEquals(ZIP_KEY, downloadOutputKey);
        cleanZip(validZip());
        stubUpload();

        assertEquals("CSV_SCAN", service.unzip(event(downloadOutputKey), context));

        String extractedKey = csvKey();
        assertEquals(
                KeyLayout.extractedCsvKey(packageVersion, Dataset.named(DATASET), DATASET + ".csv"),
                extractedKey);
        var importInput = KeyLayout.parseLandingObject(extractedKey);
        assertEquals(packageVersion, importInput.packageVersion());
        assertEquals(KeyLayout.LandingFileType.CSV, importInput.fileType());
        assertTrue(objects.containsKey(stored(CHECKED, extractedKey)));
    }

    @Test
    void recordsUploadedCsvVersionAndPublishesNextCommand() throws Exception {
        cleanZip(validZip());
        stubUpload();

        assertEquals("CSV_SCAN", service.unzip(event(), context));
        assertTrue(objects.containsKey(stored(CHECKED, csvKey())));
        var unpackState =
                uk.gov.companieshouse.addresslookup.lambda.shared.runtime.RuntimeSupport.JSON
                        .readTree(
                                objects.get(
                                        stored(
                                                "control",
                                                KeyLayout.unpackStateKey(
                                                        new KeyLayout.PackageVersion(
                                                                KeyLayout.Feed.DAILY_COU,
                                                                "20780",
                                                                "150104")))));
        assertEquals(
                "zip-version",
                unpackState.path("datasets").path(DATASET).path("zipVersion").asText());
        assertEquals(csvKey(), unpackState.path("datasets").path(DATASET).path("csvKey").asText());
        assertTrue(objects.containsKey(stored("control", KeyLayout.extractionCursorKey("20780"))));
        verify(s3)
                .getObjectTagging(
                        argThat(
                                (GetObjectTaggingRequest request) ->
                                        SOURCE.equals(request.bucket())
                                                && ZIP_KEY.equals(request.key())
                                                && "zip-version".equals(request.versionId())));
        verify(s3)
                .getObject(
                        argThat(
                                (GetObjectRequest request) ->
                                        SOURCE.equals(request.bucket())
                                                && ZIP_KEY.equals(request.key())
                                                && "zip-version".equals(request.versionId())));
        verify(commands).send("CSV_SCAN", DATASET);
    }

    @Test
    void eventFailureAfterCsvIsWritten() throws Exception {
        cleanZip(validZip());
        stubUpload();
        doThrow(new IllegalStateException("EventBridge unavailable"))
                .when(commands)
                .send("CSV_SCAN", DATASET);
        var request = event();
        assertThrows(IllegalStateException.class, () -> service.unzip(request, context));
        assertTrue(objects.containsKey(stored(CHECKED, csvKey())));
        verify(commands).send("CSV_SCAN", DATASET);
    }

    @Test
    void repeatedCallUsesUnpackStateAndRetriesEventWithoutReuploading() throws Exception {
        cleanZip(validZip());
        stubUpload();

        assertEquals("CSV_SCAN", service.unzip(event(), context));
        assertEquals("CSV_SCAN", service.unzip(event(), context));

        verify(s3, times(1)).createMultipartUpload(any(Consumer.class));
        verify(commands, times(2)).send("CSV_SCAN", DATASET);
        assertTrue(objects.containsKey(stored(CHECKED, csvKey())));
    }

    @Test
    void repeatedCallReusesExtractionWhileCsvScanIsPending() throws Exception {
        cleanZip(validZip());
        stubUpload();
        assertEquals("CSV_SCAN", service.unzip(event(), context));
        when(s3.getObjectTagging(any(GetObjectTaggingRequest.class)))
                .thenAnswer(
                        call -> {
                            var request = call.getArgument(0, GetObjectTaggingRequest.class);
                            String status =
                                    ZIP_KEY.equals(request.key()) ? "NO_THREATS_FOUND" : "PENDING";
                            return GetObjectTaggingResponse.builder()
                                    .tagSet(
                                            Tag.builder()
                                                    .key("GuardDutyMalwareScanStatus")
                                                    .value(status)
                                                    .build())
                                    .build();
                        });

        assertEquals("CSV_SCAN", service.unzip(event(), context));

        verify(s3, times(1)).createMultipartUpload(any(Consumer.class));
        verify(s3, never())
                .getObjectTagging(
                        argThat(
                                (GetObjectTaggingRequest request) ->
                                        csvKey().equals(request.key())));
        verify(commands, times(2)).send("CSV_SCAN", DATASET);
    }

    @Test
    void failedEventRetryUsesUnpackStateWithoutReuploading() throws Exception {
        cleanZip(validZip());
        stubUpload();
        doThrow(new IllegalStateException("EventBridge unavailable"))
                .when(commands)
                .send("CSV_SCAN", DATASET);
        var request = event();

        assertThrows(IllegalStateException.class, () -> service.unzip(request, context));
        doNothing().when(commands).send("CSV_SCAN", DATASET);
        assertEquals("CSV_SCAN", service.unzip(request, context));

        verify(s3, times(1)).createMultipartUpload(any(Consumer.class));
        verify(commands, times(2)).send("CSV_SCAN", DATASET);
    }

    @Test
    void recoversFromFailedChecksum() throws Exception {
        cleanZip(validZip());
        stubUpload();
        failEvidenceOnce = true;
        var request = event();

        assertThrows(S3Exception.class, () -> service.unzip(request, context));
        assertTrue(objects.containsKey(stored(CHECKED, csvKey())));
        assertEquals("CSV_SCAN", service.unzip(request, context));

        assertTrue(
                objects.containsKey(
                        stored(
                                CHECKED,
                                uk.gov.companieshouse.addresslookup.lambda.shared.storage
                                        .StreamingS3.checksumKey(csvKey()))));
        verify(commands).send("CSV_SCAN", DATASET);
    }

    @Test
    void overwritesExistingCsvForSamePackageVersion() throws Exception {
        cleanZip(validZip());
        stubUpload();
        objects.put(stored(CHECKED, csvKey()), "different CSV".getBytes(StandardCharsets.UTF_8));

        assertEquals("CSV_SCAN", service.unzip(event(), context));
        assertArrayEquals(uploaded, objects.get(stored(CHECKED, csvKey())));
        verify(commands).send("CSV_SCAN", DATASET);
    }

    // Helper methods
    private void assertPermanentFailure(
            ExtractionService extractionService, String expectedReasonCode) {
        var request = event();

        var failure =
                assertThrows(
                        PermanentFailureException.class,
                        () -> extractionService.unzip(request, context));

        assertEquals(expectedReasonCode, failure.reasonCode());
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

    private ExtractionService serviceWithLimit(long maxExtractedBytes) {
        return new ExtractionService(
                s3,
                commands,
                new AcquisitionProperties(
                        "control",
                        SOURCE,
                        "events",
                        "key",
                        "packages",
                        1024 * 1024,
                        null,
                        null,
                        maxExtractedBytes,
                        20,
                        Duration.ofSeconds(20),
                        Duration.ofSeconds(60)));
    }

    private void cleanZip(byte[] content) throws Exception {
        objects.put(stored(SOURCE, ZIP_KEY), content);
        lenient()
                .when(s3.getObjectTagging(any(GetObjectTaggingRequest.class)))
                .thenReturn(
                        GetObjectTaggingResponse.builder()
                                .tagSet(
                                        Tag.builder()
                                                .key("GuardDutyMalwareScanStatus")
                                                .value("NO_THREATS_FOUND")
                                                .build())
                                .build());
        lenient()
                .when(s3.headObject(any(HeadObjectRequest.class)))
                .thenAnswer(
                        call ->
                                HeadObjectResponse.builder()
                                        .versionId(
                                                csvKey().equals(
                                                                        call.getArgument(
                                                                                        0,
                                                                                        HeadObjectRequest
                                                                                                .class)
                                                                                .key())
                                                        ? "csv-version"
                                                        : "zip-version")
                                        .build());
    }

    private static String header() {
        return DatasetCatalog.tables().stream()
                .filter(t -> DATASET.equals(t.name()))
                .findFirst()
                .orElseThrow()
                .header();
    }

    private byte[] validZip() throws Exception {
        return zip(Map.of(DATASET + ".csv", header() + "\n"));
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

    private void stubUpload() throws Exception {
        when(s3.createMultipartUpload(any(Consumer.class)))
                .thenAnswer(
                        call -> {
                            Consumer<CreateMultipartUploadRequest.Builder> request =
                                    call.getArgument(0);
                            var builder = CreateMultipartUploadRequest.builder();
                            request.accept(builder);
                            assertEquals(CHECKED, builder.build().bucket());
                            assertEquals(csvKey(), builder.build().key());
                            return CreateMultipartUploadResponse.builder()
                                    .uploadId("upload-id")
                                    .build();
                        });
        lenient()
                .when(s3.uploadPart(any(Consumer.class), any(RequestBody.class)))
                .thenAnswer(
                        call -> {
                            try (var in =
                                    call.getArgument(1, RequestBody.class)
                                            .contentStreamProvider()
                                            .newStream()) {
                                uploaded = in.readAllBytes();
                            }
                            return UploadPartResponse.builder().eTag("etag").build();
                        });
        lenient()
                .when(s3.completeMultipartUpload(any(Consumer.class)))
                .thenAnswer(
                        call -> {
                            objects.put(stored(CHECKED, csvKey()), uploaded);
                            return CompleteMultipartUploadResponse.builder()
                                    .versionId("csv-version")
                                    .build();
                        });
    }

    private PutObjectResponse recordObject(PutObjectRequest request, RequestBody body)
            throws Exception {
        if (failEvidenceOnce && request.key().startsWith("control/metadata/checksums/")) {
            failEvidenceOnce = false;
            throw S3Exception.builder().statusCode(503).message("S3 unavailable").build();
        }
        try (var in = body.contentStreamProvider().newStream()) {
            byte[] contents = in.readAllBytes();
            objects.put(stored(request.bucket(), request.key()), contents);
        }
        return PutObjectResponse.builder().build();
    }

    private static String csvKey() {
        return "landing/daily_cou/20780/150104/downloads/" + DATASET + "/" + DATASET + ".csv";
    }

    private static StoredObject stored(String bucket, String key) {
        return new StoredObject(bucket, key);
    }

    private static Map<String, Object> event() {
        return event(1, ZIP_KEY);
    }

    private static Map<String, Object> event(int schemaVersion) {
        return event(schemaVersion, ZIP_KEY);
    }

    private static Map<String, Object> event(String zipKey) {
        return event(1, zipKey);
    }

    private static Map<String, Object> event(int schemaVersion, String zipKey) {
        return Map.of(
                "detail",
                Map.of(
                        "schemaVersion", schemaVersion,
                        "releaseId", ID,
                        "dataset", DATASET,
                        "object",
                                Map.of(
                                        "bucket",
                                        SOURCE,
                                        "key",
                                        zipKey,
                                        "versionId",
                                        "zip-version")));
    }
}
