package uk.gov.companieshouse.addresslookup.lambda.unzip.service;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.argThat;
import static org.mockito.Mockito.*;

import com.amazonaws.services.lambda.runtime.Context;
import com.fasterxml.jackson.databind.JsonNode;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.api.function.Executable;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import uk.gov.companieshouse.addresslookup.lambda.shared.acquisition.AcquisitionProperties;
import uk.gov.companieshouse.addresslookup.lambda.shared.storage.ReleaseStore;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.function.Consumer;
import java.util.zip.CRC32;
import java.util.zip.ZipEntry;
import java.util.zip.ZipInputStream;
import java.util.zip.ZipOutputStream;
import org.junit.jupiter.api.BeforeEach;
import software.amazon.awssdk.core.ResponseInputStream;
import software.amazon.awssdk.core.sync.RequestBody;
import software.amazon.awssdk.http.AbortableInputStream;
import software.amazon.awssdk.services.s3.S3Client;
import software.amazon.awssdk.services.s3.model.CompleteMultipartUploadResponse;
import software.amazon.awssdk.services.s3.model.CopyObjectRequest;
import software.amazon.awssdk.services.s3.model.CopyObjectResponse;
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
import uk.gov.companieshouse.addresslookup.lambda.shared.acquisition.Commands;
import uk.gov.companieshouse.release.model.DatasetCatalog;

@ExtendWith(MockitoExtension.class)
final class ExtractionServiceTest {
  private static final String DATASET = "add_gb_royalmailaddress";
  private static final String ID = "12345678-1234-1234-1234-123456789abc";
  private static final String ZIP_KEY = "Scanned/" + ID + "/" + DATASET + ".zip";
  private static final String RECEIPT_KEY = ReleaseStore.receiptKey(ID, DATASET, "extract");
  private static final String SOURCE = "source";
  private static final String CHECKED = "checked";

  private record StoredObject(String bucket, String key) {}

  @Mock private S3Client s3;
  @Mock private Commands commands;
  @Mock private Context context;
  private final Map<StoredObject, byte[]> objects = new HashMap<>();
  private byte[] uploaded;
  private boolean failReceiptOnce;
  private boolean failEvidenceOnce;
  private boolean conflictReceiptOnce;
  private boolean conflictingReceiptOnce;
  private ExtractionComponent service;

  @BeforeEach
  void setUp() throws Exception {
    service =
        new ExtractionComponent(
            s3,
            new AcquisitionProperties(
                SOURCE,
                CHECKED,
                "ingest",
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
        .thenAnswer(call -> {
          var request = call.getArgument(0, ListObjectsV2Request.class);
          return objects.containsKey(stored(request.bucket(), request.prefix()))
              ? ListObjectsV2Response.builder()
                  .contents(S3Object.builder().key(request.prefix()).build())
                  .build()
              : ListObjectsV2Response.builder().build();
        });
    lenient()
        .when(s3.getObject(any(GetObjectRequest.class)))
        .thenAnswer(call -> {
          var request = call.getArgument(0, GetObjectRequest.class);
          byte[] content = objects.get(stored(request.bucket(), request.key()));
          if (content == null)
            throw new IllegalStateException(
                "Missing fixture: " + request.bucket() + "/" + request.key());
          return new ResponseInputStream<>(
              GetObjectResponse.builder().build(),
              AbortableInputStream.create(new ByteArrayInputStream(content)));
        });
    lenient()
        .when(s3.putObject(any(PutObjectRequest.class), any(RequestBody.class)))
        .thenAnswer(call ->
            recordObject(
                call.getArgument(0, PutObjectRequest.class),
                call.getArgument(1, RequestBody.class)));
    lenient()
        .when(s3.putObject(any(Consumer.class), any(RequestBody.class)))
        .thenAnswer(call -> {
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
    }

  @Test
  void waitsForPendingZipScan() throws Exception {
    objects.put(stored(SOURCE, ZIP_KEY), validZip());
    when(context.getAwsRequestId()).thenReturn("request-id");
    when(s3.getObjectTagging(any(GetObjectTaggingRequest.class)))
        .thenReturn(GetObjectTaggingResponse.builder().build());

    assertThrows(IllegalStateException.class, () -> service.unzip(event(), context));
        verify(s3, never()).getObject(argThat((GetObjectRequest request) -> ZIP_KEY.equals(request.key())));
    }

  @Test
  void rejectsMismatchedHeaderWithoutUploading() throws Exception {
    cleanZip(zip(Map.of(DATASET + ".csv", "wrong,header\n1,2\n")));

    assertRejected("HEADER_MISMATCH", () -> service.unzip(event(), context));
        verify(s3, never()).createMultipartUpload(any(Consumer.class));
    }

  @Test
  void rejectsUnexpectedCsvFilenameWithoutUploading() throws Exception {
    cleanZip(zip(Map.of("unexpected.csv", "data\n")));

    assertRejected("ARCHIVE_INVALID", () -> service.unzip(event(), context));
        verify(s3, never()).createMultipartUpload(any(Consumer.class));
    }

    @ParameterizedTest
    @ValueSource(strings = {"notes.txt", "add_gb_royalmailaddress_notes.json",
            "add_gb_royalmailaddress_.csv", "metadata/"})
    void rejectsUnexpectedEntriesBeforePrimaryCsv(String name) throws Exception {
        cleanZip(zip(Map.of(name, "data\n")));

        assertRejected("ARCHIVE_INVALID", () -> service.unzip(event(), context));
        verify(s3, never()).createMultipartUpload(any(Consumer.class));
    }

  @ParameterizedTest
    @ValueSource(strings = {"notes.txt", "add_gb_royalmailaddress_notes.json",
            "add_gb_royalmailaddress_.csv", "metadata/"})
    void rejectsUnexpectedEntriesAfterPrimaryCsvBeforeCompletingUpload(String name) throws Exception {
        var entries = new LinkedHashMap<String, String>();
        entries.put(DATASET + ".csv", header() + "\n");
        entries.put(name, "data\n");
        cleanZip(zip(entries));
        stubUpload();

        assertRejected("ARCHIVE_INVALID", () -> service.unzip(event(), context));
        verify(s3, never()).completeMultipartUpload(any(Consumer.class));
        verify(s3).abortMultipartUpload(any(Consumer.class));
        assertFalse(objects.containsKey(stored(SOURCE, RECEIPT_KEY)));
    }

    @Test
    void acceptsMetadataAfterPrimaryCsv() throws Exception {
        var entries = new LinkedHashMap<String, String>();
        entries.put(DATASET + ".csv", header() + "\n");
        entries.put(DATASET + "_multires.csv", "a,b\n");
        cleanZip(zip(entries));
        stubUpload();

        assertEquals("CSV_SCAN", service.unzip(event(), context));
        assertEquals(2, capturedReceipt().path("zipFileCount").asInt());
        assertEquals((header() + "\n").getBytes(StandardCharsets.UTF_8).length, uploaded.length);
    }

    @Test
    void rejectsHighlyCompressedMetadataAfterPrimaryCsv() throws Exception {
        var entries = new LinkedHashMap<String, String>();
        entries.put(DATASET + ".csv", header() + "\n");
        entries.put(DATASET + "_multires.csv", "x".repeat(30_000));
        cleanZip(zip(entries));
        stubUpload();

        assertRejected("MALFORMED_ZIP", () -> service.unzip(event(), context));
        verify(s3, never()).completeMultipartUpload(any(Consumer.class));
        verify(s3).abortMultipartUpload(any(Consumer.class));
    }

    @Test
  void rejectsZipMembersWithPaths() throws Exception {
    for (String name : new String[] {"nested/" + DATASET + ".csv", "..", "dir\\file.csv"}) {
      cleanZip(zip(Map.of(name, "data\n")));

      assertRejected("ARCHIVE_INVALID", () -> service.unzip(event(), context));
        }
    verify(s3, never()).createMultipartUpload(any(Consumer.class));
    }

    @Test
    void rejectsArchiveWithTooManyEntries() throws Exception {
        var entries = new LinkedHashMap<String, String>();
        for (int i = 0; i < 101; i++) {
            entries.put(DATASET + "_part" + i + ".csv", "a,b\n" + i + "\n");
        }
        cleanZip(zip(entries));

        assertRejected("ARCHIVE_INVALID", () -> service.unzip(event(), context));
    verify(s3, never()).createMultipartUpload(any(Consumer.class));
  }

  @Test
  void rejectsAncillaryEntriesThatExceedTheExpandedSizeLimit() throws Exception {
    cleanZip(zip(Map.of(DATASET + "_big.csv", "x".repeat(200))));

    assertRejected("MALFORMED_ZIP", () -> serviceWithLimit(100).unzip(event(), context));
        verify(s3, never()).createMultipartUpload(any(Consumer.class));
    }

  @Test
  void rejectsPrimaryCsvThatExceedsTheExpandedSizeLimit() throws Exception {
    cleanZip(zip(Map.of(DATASET + ".csv", header() + "\n" + "row\n".repeat(100))));
    stubUpload();

    assertRejected("MALFORMED_ZIP", () ->
                serviceWithLimit(header().getBytes(StandardCharsets.UTF_8).length + 10)
                        .unzip(event(), context));
        verify(s3, never()).completeMultipartUpload(any(Consumer.class));
        verify(s3).abortMultipartUpload(any(Consumer.class));
    }

    @Test
    void rejectsHighlyCompressedAncillaryEntry() throws Exception {
        byte[] archive = zip(Map.of(DATASET + "_extra.csv", "x".repeat(30_000)));
        assertTrue(compressionRatio(archive) > 100);
        cleanZip(archive);

        assertRejected("MALFORMED_ZIP", () -> service.unzip(event(), context));
        verify(s3, never()).createMultipartUpload(any(Consumer.class));
    }

    @Test
    void rejectsArchiveExceedingTotalCompressionRatio() throws Exception {
        var entries = new LinkedHashMap<String, String>();
        entries.put(DATASET + "_first.csv", "a,b\n");
        entries.put(DATASET + "_second.csv", "x".repeat(1_000));
        byte[] archive = zip(entries);
        assertTrue(archiveCompressionRatio(archive) > 20);
        assertTrue(compressionRatio(zip(Map.of(DATASET + "_second.csv", "x".repeat(1_000)))) < 100);
        cleanZip(archive);

        assertRejected("MALFORMED_ZIP", () -> service.unzip(event(), context));
        verify(s3, never()).createMultipartUpload(any(Consumer.class));
    }

    @Test
    void abortsUploadWhenPrimaryCsvExceedsCompressionRatio() throws Exception {
        cleanZip(zip(Map.of(DATASET + ".csv", header() + "\n" + "x".repeat(30_000))));
        stubUpload();

        assertRejected("MALFORMED_ZIP", () -> service.unzip(event(), context));
    verify(s3, never()).completeMultipartUpload(any(Consumer.class));
    verify(s3).abortMultipartUpload(any(Consumer.class));
  }

  @Test
    void acceptsStoredPrimaryCsv() throws Exception {
        byte[] csv = (header() + "\n").getBytes(StandardCharsets.UTF_8);
        var crc = new CRC32();
        crc.update(csv);
        var out = new ByteArrayOutputStream();
        try (var zip = new ZipOutputStream(out)) {
            var entry = new ZipEntry(DATASET + ".csv");
            entry.setMethod(ZipEntry.STORED);
            entry.setSize(csv.length);
            entry.setCompressedSize(csv.length);
            entry.setCrc(crc.getValue());
            zip.putNextEntry(entry);
            zip.write(csv);
            zip.closeEntry();
        }
        cleanZip(out.toByteArray());
        stubUpload();

        assertEquals("CSV_SCAN", service.unzip(event(), context));
        assertEquals(csvKey(), capturedReceipt().path("key").asText());
    }

    @Test
    void extractsValidIslCsv() throws Exception {
        String dataset = "add_isl_royalmailaddress";
        String zipKey = "Scanned/" + ID + "/" + dataset + ".zip";
        String csvKey = ReleaseStore.extractedCsvKey(ID, dataset);
        String islHeader = DatasetCatalog.tables().stream()
                .filter(table -> dataset.equals(table.name())).findFirst().orElseThrow().header();
        cleanZip(zipKey, zip(Map.of(dataset + ".csv", islHeader + "\n")));
        stubUpload(csvKey);
        var request = Map.<String, Object>of("detail", Map.of(
                "schemaVersion", 1, "releaseId", ID, "dataset", dataset,
                "object", Map.of("bucket", SOURCE, "key", zipKey, "versionId", "zip-version")));

        assertEquals("CSV_SCAN", service.unzip(request, context));
        assertTrue(objects.containsKey(stored(CHECKED, csvKey)));
        assertTrue(objects.containsKey(stored(SOURCE, ReleaseStore.receiptKey(ID, dataset, "extract"))));
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
  void constructorRequiresScannedBucket() {
    var properties =
        new AcquisitionProperties(
            SOURCE,
            " ",
            "ingest",
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
            IllegalArgumentException.class, () -> new ExtractionComponent(s3, properties));

    assertEquals("Missing SCANNED_BUCKET", failure.getMessage());
  }

  @Test
  void reportsMissingPrimaryCsv() throws Exception {
    cleanZip(zip(Map.of(DATASET + "_multires.csv", "data\n")));

    assertRejected("CSV_NOT_FOUND", () -> service.unzip(event(), context));
        verify(s3, never()).createMultipartUpload(any(Consumer.class));
    }

  @Test
  void reportsMalformedZip() throws Exception {
    byte[] malformed = zip(Map.of(DATASET + "_multires.csv", "data\n"));
    malformed[8] = 99;
    cleanZip(malformed);

    assertRejected("MALFORMED_ZIP", () -> service.unzip(event(), context));
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
    }

  @Test
  void rejectsUnsupportedExtractRequestVersion() throws Exception {
    cleanZip(validZip());
    var request = event(2);

        assertThrows(IllegalArgumentException.class, () -> service.unzip(request, context));
        verify(s3, never()).createMultipartUpload(any(Consumer.class));
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
  void recordsUploadedCsvVersion() throws Exception {
    cleanZip(validZip());
    stubUpload();

    assertEquals("CSV_SCAN", service.unzip(event(), context));
    JsonNode receipt = capturedReceipt();
    assertEquals(ID, receipt.path("releaseId").asText());
    assertEquals(DATASET, receipt.path("dataset").asText());
    assertEquals("extracted/" + ID + "/" + DATASET + "/" + DATASET + ".csv", receipt.path("key").asText());
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
    }

  @Test
  void repeatedCallsDoesNotExtractAgain() throws Exception {
    cleanZip(validZip());
    stubUpload();

    assertEquals("CSV_SCAN", service.unzip(event(), context));
    assertEquals("CSV_SCAN", service.unzip(event(), context));

        verify(s3, times(1)).createMultipartUpload(any(Consumer.class));
        assertEquals(csvKey(), capturedReceipt().path("key").asText());
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
    }

  @Test
  void acceptsMatchingReceipt() throws Exception {
    cleanZip(validZip());
    stubUpload();
    conflictReceiptOnce = true;

    assertEquals("CSV_SCAN", service.unzip(event(), context));

        assertEquals("csv-version", capturedReceipt().path("version").asText());
    }

  @Test
  void rejectsConflictingReceipt() throws Exception {
    cleanZip(validZip());
    stubUpload();
    conflictingReceiptOnce = true;
    var request = event();

    assertThrows(IllegalArgumentException.class, () -> service.unzip(request, context));

    }

  @Test
  void rejectsCsvWithDifferentContent() throws Exception {
    cleanZip(validZip());
    stubUpload();
    objects.put(stored(CHECKED, csvKey()), "different CSV".getBytes(StandardCharsets.UTF_8));
    var request = event();

        assertThrows(IllegalArgumentException.class, () -> service.unzip(request, context));
    }

  @Test
  void rejectsReceiptForDifferentZipVersion() throws Exception {
    cleanZip(validZip());
    var receipt =
        """
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
        """
            .formatted(ID, DATASET, ZIP_KEY, csvKey());
    objects.put(stored(SOURCE, RECEIPT_KEY), receipt.getBytes(StandardCharsets.UTF_8));
    var request = event();

        assertThrows(IllegalArgumentException.class, () -> service.unzip(request, context));
        verify(s3, never()).createMultipartUpload(any(Consumer.class));
    }

  // Helper methods
  private ExtractionComponent serviceWithLimit(long maxExtractedBytes) {
    return new ExtractionComponent(
                s3,
        new AcquisitionProperties(
            SOURCE,
            CHECKED,
            "ingest",
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
        cleanZip(ZIP_KEY, content);
    }

    private void cleanZip(String key, byte[] content) throws Exception {
        objects.put(stored(SOURCE, key), content);
        lenient().when(s3.getObjectTagging(any(GetObjectTaggingRequest.class)))
                .thenReturn(
                        GetObjectTaggingResponse.builder()
                                .tagSet(Tag.builder().key("GuardDutyMalwareScanStatus").value("NO_THREATS_FOUND").build())
                                .build());
        lenient().when(s3.headObject(any(HeadObjectRequest.class)))
                .thenAnswer(
                        call -> {
                            var request = call.getArgument(0, HeadObjectRequest.class);
                            byte[] object = objects.get(stored(request.bucket(), request.key()));
                            return HeadObjectResponse.builder()
                                    .versionId(CHECKED.equals(request.bucket())
                                            ? "csv-version" : "zip-version")
                                    .contentLength(object == null ? 0L : (long) object.length)
                                    .build();
                        });
        lenient().when(s3.copyObject(any(Consumer.class)))
                .thenAnswer(call -> {
                    var builder = CopyObjectRequest.builder();
                    Consumer<CopyObjectRequest.Builder> request = call.getArgument(0);
                    request.accept(builder);
                    var copy = builder.build();
                    assertEquals(SOURCE, copy.sourceBucket());
                    assertEquals(key, copy.sourceKey());
                    assertEquals("zip-version", copy.sourceVersionId());
                    assertEquals(SOURCE, copy.destinationBucket());
                    objects.put(stored(copy.destinationBucket(), copy.destinationKey()), content);
                    return CopyObjectResponse.builder().build();
                });
    }

    private void assertRejected(String code, Executable operation) throws Exception {
        var failure = assertThrows(ExtractionComponent.PermanentExtractionException.class, operation);
        assertEquals(code, failure.code());
        String key = "quarantine/" + ID + "/" + DATASET + "/"
                + uk.gov.companieshouse.addresslookup.lambda.shared.runtime.RuntimeSupport.sha(
                        "zip-version".getBytes(StandardCharsets.UTF_8)) + ".zip";
        byte[] quarantined = objects.get(stored(SOURCE, key));
        assertNotNull(quarantined);
        assertArrayEquals(objects.get(stored(SOURCE, ZIP_KEY)), quarantined);
        assertFalse(objects.containsKey(stored(SOURCE, RECEIPT_KEY)));
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

  private static double compressionRatio(byte[] archive) throws IOException {
        try (var zip = new ZipInputStream(new ByteArrayInputStream(archive))) {
            ZipEntry entry = zip.getNextEntry();
            int expanded = zip.readAllBytes().length;
            return (double) expanded / entry.getCompressedSize();
        }
    }

    private static double archiveCompressionRatio(byte[] archive) throws IOException {
        try (var zip = new ZipInputStream(new ByteArrayInputStream(archive))) {
            long expanded = 0;
            long compressed = 0;
            ZipEntry entry;
            while ((entry = zip.getNextEntry()) != null) {
                expanded += zip.readAllBytes().length;
                compressed += entry.getCompressedSize();
            }
            return (double) expanded / compressed;
        }
    }

    private void stubUpload() throws Exception {
        stubUpload(csvKey());
    }

    private void stubUpload(String key) throws Exception {
        when(s3.createMultipartUpload(any(Consumer.class)))
                .thenAnswer(
                        call -> {
                            Consumer<CreateMultipartUploadRequest.Builder> request = call.getArgument(0);
                            var builder = CreateMultipartUploadRequest.builder();
                            request.accept(builder);
                            assertEquals(CHECKED, builder.build().bucket());
                            assertEquals(key, builder.build().key());
          return CreateMultipartUploadResponse.builder().uploadId("upload-id").build();
        });
    lenient()
        .when(s3.uploadPart(any(Consumer.class), any(RequestBody.class)))
        .thenAnswer(call -> {
          try (var in =
              call.getArgument(1, RequestBody.class).contentStreamProvider().newStream()) {
            uploaded = in.readAllBytes();
          }
          return UploadPartResponse.builder().eTag("etag").build();
        });
    lenient()
        .when(s3.completeMultipartUpload(any(Consumer.class)))
        .thenAnswer(call -> {
          if (objects.containsKey(stored(CHECKED, key)))
            throw S3Exception.builder().statusCode(412).build();
          objects.put(stored(CHECKED, key), uploaded);
          return CompleteMultipartUploadResponse.builder().versionId("csv-version").build();
        });
  }

  private JsonNode capturedReceipt() throws Exception {
    assertTrue(objects.containsKey(stored(SOURCE, RECEIPT_KEY)));
    return uk.gov.companieshouse.addresslookup.lambda.shared.runtime.RuntimeSupport.JSON.readTree(
        objects.get(stored(SOURCE, RECEIPT_KEY)));
  }

  private PutObjectResponse recordObject(PutObjectRequest request, RequestBody body)
      throws Exception {
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
        var conflicting =
            uk.gov.companieshouse.addresslookup.lambda.shared.runtime.RuntimeSupport.JSON
                .readTree(contents)
                .deepCopy();
        ((com.fasterxml.jackson.databind.node.ObjectNode) conflicting)
            .put("zipVersion", "other-version");
        objects.put(
            stored(request.bucket(), request.key()),
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
    return Map.of(
        "detail",
        Map.of(
            "schemaVersion", schemaVersion,
            "releaseId", ID,
            "dataset", DATASET,
            "object", Map.of("bucket", SOURCE, "key", ZIP_KEY, "versionId", "zip-version")));
  }
}
