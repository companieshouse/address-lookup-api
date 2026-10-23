package uk.gov.companieshouse.addresslookup.lambda.shared.acquisition;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.argThat;
import static org.mockito.Mockito.*;

import com.amazonaws.services.lambda.runtime.Context;
import com.fasterxml.jackson.databind.JsonNode;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import uk.gov.companieshouse.addresslookup.lambda.shared.storage.ReleaseStore;
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

import org.junit.jupiter.api.BeforeEach;
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
import uk.gov.companieshouse.release.model.DatasetCatalog;

@ExtendWith(MockitoExtension.class)
final class ExtractionServiceTest {
    private static final String DATASET = "add_gb_royalmailaddress";
    private static final String ID = "12345678-1234-1234-1234-123456789abc";
    private static final String ZIP_KEY = "Scanned/" + ID + "/" + DATASET + ".zip";
    private static final String RECEIPT_KEY = ReleaseStore.receiptKey(ID, DATASET, "extract");
    private static final String SOURCE = "source";
    private static final String CHECKED = "checked";

    private record StoredObject(String bucket, String key) {
    }

    @Mock
    private S3Client s3;
    @Mock
    private Commands commands;
    @Mock
    private Context context;
    private final Map<StoredObject, byte[]> objects = new HashMap<>();
    private byte[] uploaded;
    private boolean failReceiptOnce;
    private boolean failEvidenceOnce;
    private boolean conflictReceiptOnce;
    private boolean conflictingReceiptOnce;
    private ExtractionService service;

    @BeforeEach
    void setUp() throws Exception {
        service = new ExtractionService(
                s3,
                commands,
                new AcquisitionProperties(
                        SOURCE, CHECKED, "ingest", "events", "key", "packages",
                        1024 * 1024, 1024 * 1024, 20,
                        Duration.ofSeconds(20), Duration.ofSeconds(60)));

        lenient().when(context.getRemainingTimeInMillis()).thenReturn(900_000);
        lenient().when(s3.listObjectsV2(any(ListObjectsV2Request.class)))
                .thenAnswer(
                        call -> {
                            var request = call.getArgument(0, ListObjectsV2Request.class);
                            return objects.containsKey(stored(request.bucket(), request.prefix()))
                                    ? ListObjectsV2Response.builder()
                                    .contents(S3Object.builder().key(request.prefix()).build())
                                    .build()
                                    : ListObjectsV2Response.builder().build();
                        });
        lenient().when(s3.getObject(any(GetObjectRequest.class)))
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
        lenient().when(s3.putObject(any(PutObjectRequest.class), any(RequestBody.class)))
                .thenAnswer(
                        call -> recordObject(
                                call.getArgument(0, PutObjectRequest.class),
                                call.getArgument(1, RequestBody.class)));
        lenient().when(s3.putObject(any(Consumer.class), any(RequestBody.class)))
                .thenAnswer(
                        call -> {
                            var builder = PutObjectRequest.builder();
                            Consumer<PutObjectRequest.Builder> request = call.getArgument(0);
                            request.accept(builder);
                            return recordObject(builder.build(), call.getArgument(1, RequestBody.class));
                        });
    }

    @Test
    void rejectsMissingSourceZip() throws Exception {
        when(s3.getObjectTagging(any(GetObjectTaggingRequest.class)))
                .thenThrow(S3Exception.builder().statusCode(404).build());
        var unzipEvent = event();

        assertThrows(S3Exception.class, () -> service.unzip(unzipEvent, context));
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
    void rejectsZipMembersWithPaths() throws Exception {
        for (String name : new String[]{"nested/" + DATASET + ".csv", "..", "dir\\file.csv"}) {
            cleanZip(zip(Map.of(name, "data\n")));

            assertEquals("ARCHIVE_INVALID", service.unzip(event(), context), name);
        }
        verify(s3, never()).createMultipartUpload(any(Consumer.class));
    }

    @Test
    void rejectsAncillaryEntriesThatExceedTheExpandedSizeLimit() throws Exception {
        cleanZip(zip(Map.of(DATASET + "_big.csv", "x".repeat(200))));

        assertEquals("MALFORMED_ZIP", serviceWithLimit(100).unzip(event(), context));
        verify(s3, never()).createMultipartUpload(any(Consumer.class));
    }

    @Test
    void rejectsPrimaryCsvThatExceedsTheExpandedSizeLimit() throws Exception {
        cleanZip(zip(Map.of(DATASET + ".csv", header() + "\n" + "row\n".repeat(100))));
        stubUpload();

        assertEquals("MALFORMED_ZIP", serviceWithLimit(100).unzip(event(), context));
        verify(s3, never()).completeMultipartUpload(any(Consumer.class));
        verify(s3).abortMultipartUpload(any(Consumer.class));
    }

    @Test
    void failsWhenDeadlineApproachesWhileSkippingAncillaryEntries() throws Exception {
        cleanZip(zip(Map.of(DATASET + "_extra.csv", "a,b\n")));
        when(context.getRemainingTimeInMillis()).thenReturn(1_000);
        var request = event();

        var failure = assertThrows(IllegalArgumentException.class, () -> service.unzip(request, context));

        assertEquals("Unzip deadline", failure.getMessage());
    }

    @Test
    void constructorRequiresScannedBucket() {
        var properties = new AcquisitionProperties(
                SOURCE, " ", "ingest", "events", "key", "packages", 1024, 1024, 20,
                Duration.ofSeconds(20), Duration.ofSeconds(60));

        var failure = assertThrows(IllegalArgumentException.class,
                () -> new ExtractionService(s3, commands, properties));

        assertEquals("Missing SCANNED_BUCKET", failure.getMessage());
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
        JsonNode receipt = capturedReceipt();
        assertEquals(2, receipt.path("zipFileCount").asInt());
        assertEquals(csvKey(), receipt.path("key").asText());
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
        var request = event();
        assertThrows(IllegalStateException.class, () -> service.unzip(request, context));
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
        var request = event();

        assertThrows(IllegalStateException.class, () -> service.unzip(request, context));
        doNothing().when(commands).send("CSV_SCAN", DATASET);
        assertEquals("CSV_SCAN", service.unzip(request, context));

        verify(s3, times(1)).createMultipartUpload(any(Consumer.class));
        verify(commands, times(2)).send("CSV_SCAN", DATASET);
    }

    @Test
    void recoversFromFailedReceipt() throws Exception {
        cleanZip(validZip());
        stubUpload();
        failReceiptOnce = true;
        var request = event();

        assertThrows(S3Exception.class, () -> service.unzip(request, context));
        assertTrue(objects.containsKey(stored(CHECKED, csvKey())));
        assertEquals("CSV_SCAN", service.unzip(request, context));

        verify(s3, times(2)).createMultipartUpload(any(Consumer.class));
        verify(commands).send("CSV_SCAN", DATASET);
        assertEquals("csv-version", capturedReceipt().path("version").asText());
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
        var request = event();

        assertThrows(IllegalArgumentException.class, () -> service.unzip(request, context));

        verifyNoInteractions(commands);
    }

    @Test
    void rejectsCsvWithDifferentContent() throws Exception {
        cleanZip(validZip());
        stubUpload();
        objects.put(stored(CHECKED, csvKey()), "different CSV".getBytes(StandardCharsets.UTF_8));
        var request = event();

        assertThrows(IllegalArgumentException.class, () -> service.unzip(request, context));
        verifyNoInteractions(commands);
    }

    @Test
    void rejectsReceiptForDifferentZipVersion() throws Exception {
        cleanZip(validZip());
        var receipt = """
                {
                  "releaseId": "%s",
                  "dataset": "%s",
                  "zipS3Key": "%s",
                  "zipVersion": "other-version",
                  "key": "%s",
                  "status": "EXTRACTED",
                  "version": "csv-version",
                  "sha256": "abc"
                }
                """.formatted(ID, DATASET, ZIP_KEY, csvKey());
        objects.put(stored(SOURCE, RECEIPT_KEY), receipt.getBytes(StandardCharsets.UTF_8));
        var request = event();

        assertThrows(IllegalArgumentException.class, () -> service.unzip(request, context));
        verify(s3, never()).createMultipartUpload(any(Consumer.class));
        verifyNoInteractions(commands);
    }

    // Helper methods
    private ExtractionService serviceWithLimit(long maxExtractedBytes) {
        return new ExtractionService(
                s3, commands,
                new AcquisitionProperties(
                        SOURCE, CHECKED, "ingest", "events", "key", "packages", 1024 * 1024,
                        maxExtractedBytes, 20, Duration.ofSeconds(20), Duration.ofSeconds(60)));
    }

    private void cleanZip(byte[] content) throws Exception {
        objects.put(stored(SOURCE, ZIP_KEY), content);
        lenient().when(s3.getObjectTagging(any(GetObjectTaggingRequest.class)))
                .thenReturn(
                        GetObjectTaggingResponse.builder()
                                .tagSet(Tag.builder().key("GuardDutyMalwareScanStatus").value("NO_THREATS_FOUND").build())
                                .build());
        lenient().when(s3.headObject(any(HeadObjectRequest.class)))
                .thenAnswer(
                        call -> HeadObjectResponse.builder()
                                .versionId(CHECKED.equals(call.getArgument(0, HeadObjectRequest.class).bucket())
                                        ? "csv-version" : "zip-version")
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
                            Consumer<CreateMultipartUploadRequest.Builder> request = call.getArgument(0);
                            var builder = CreateMultipartUploadRequest.builder();
                            request.accept(builder);
                            assertEquals(CHECKED, builder.build().bucket());
                            assertEquals(csvKey(), builder.build().key());
                            return CreateMultipartUploadResponse.builder().uploadId("upload-id").build();
                        });
        lenient().when(s3.uploadPart(any(Consumer.class), any(RequestBody.class)))
                .thenAnswer(
                        call -> {
                            try (var in = call.getArgument(1, RequestBody.class)
                                    .contentStreamProvider().newStream()) {
                                uploaded = in.readAllBytes();
                            }
                            return UploadPartResponse.builder().eTag("etag").build();
                        });
        lenient().when(s3.completeMultipartUpload(any(Consumer.class)))
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
