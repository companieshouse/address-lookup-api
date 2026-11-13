package uk.gov.companieshouse.addresslookup.lambda.unzip.service;

import static uk.gov.companieshouse.addresslookup.lambda.shared.acquisition.AcquisitionSupport.*;
import static uk.gov.companieshouse.addresslookup.lambda.shared.runtime.RuntimeSupport.*;

import com.amazonaws.services.lambda.runtime.Context;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.jspecify.annotations.NonNull;
import org.springframework.stereotype.Component;
import software.amazon.awssdk.services.s3.S3Client;
import software.amazon.awssdk.services.s3.model.GetObjectRequest;
import software.amazon.awssdk.services.s3.model.S3Exception;
import uk.gov.companieshouse.addresslookup.lambda.shared.acquisition.AcquisitionProperties;
import uk.gov.companieshouse.addresslookup.lambda.shared.storage.ReleaseStore;
import uk.gov.companieshouse.addresslookup.lambda.shared.storage.StreamingS3;
import uk.gov.companieshouse.release.model.DatasetCatalog;

import java.io.*;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.zip.ZipEntry;
import java.util.zip.ZipException;
import java.util.zip.ZipInputStream;
import org.apache.commons.csv.CSVFormat;
import org.apache.commons.csv.CSVParser;
import uk.gov.companieshouse.logging.Logger;
import uk.gov.companieshouse.logging.LoggerFactory;

/**
 * Red-zone extraction reads only the exact clean ZIP version. Members must be the primary
 * dataset CSV or dataset-prefixed ancillary CSVs.
 */
@Component
public final class ExtractionComponent {
    private final S3Client s3;
    private final AcquisitionProperties properties;
    private final ZipQuarantineStore quarantine;
    private static final int MAX_CSV_HEADER_BYTES = 64 * 1024;
    private static final int MAX_ENTRY_COMPRESSION_RATIO = 100;
    private static final int MAX_ARCHIVE_COMPRESSION_RATIO = 20;
    private static final String DATASET_FIELD = "dataset";
    private static final String RELEASE_ID_FIELD = "releaseId";
    private static final String REQUEST_ID_FIELD = "request_id";
    private static final String SCHEMA_VERSION_FIELD = "schemaVersion";
    private static final String EXTRACT_FIELD = "extract";
    private static final String VERSION_FIELD = "version";
    private static final String SHA256_FIELD = "sha256";
    private static final String CSV_SCAN_STATUS = "CSV_SCAN";
    private static final String CSV_NOT_FOUND = "CSV_NOT_FOUND";
    private static final String STATUS = "status";
    private static final long NANOS_PER_MILLI = 1_000_000;
    private static final Logger LOG = LoggerFactory.getLogger(ExtractionComponent.class.getName());

  private record ReceiptIdentity(
      String releaseId,
      String dataset,
      String zipKey,
      String zipVersion,
      String csvKey,
      String schemaVersion) {}

  static class CsvHeaderException extends Exception {
    CsvHeaderException(String msg) {
      super(msg);
    }
  }

  static class ArchiveValidationException extends IOException {
    ArchiveValidationException(String msg) {
      super(msg);
    }
  }

  static class MissingPrimaryCsvException extends Exception {
    MissingPrimaryCsvException(String msg) {
      super(msg);
    }
  }

    public static final class PermanentExtractionException extends Exception {
        private final String code;

        private PermanentExtractionException(String code, Exception cause) {
            super("Rejected ZIP: " + code, cause);
            this.code = code;
        }

        public String code() {
            return code;
        }
    }

    public ExtractionComponent(S3Client s3, AcquisitionProperties properties) {
        this.s3 = s3;
        this.properties = properties;
        AcquisitionProperties.required(properties.sourceBucket(), "SOURCE_BUCKET");
        AcquisitionProperties.required(properties.scannedBucket(), "SCANNED_BUCKET");
        this.quarantine = new ZipQuarantineStore(s3, properties.maxZipBytes());
    }

  public String unzip(Map<String, Object> event, Context context) throws Exception {
    // Validate input
    JsonNode input = detail(event);
    String dataset = required(input, DATASET_FIELD);
    check(dataset.matches("[a-z][a-z0-9_]*"), "Invalid dataset");
    var table = table(dataset);

    String releaseId = required(input, RELEASE_ID_FIELD);
    LOG.info(
        "Extraction started",
        Map.of(
            REQUEST_ID_FIELD, requestId(context), RELEASE_ID_FIELD, releaseId, DATASET_FIELD, dataset));
    JsonNode scanObject = input.path("object");
    String scanBucket = required(scanObject, "bucket");
    String s3Key = required(scanObject, "key");
    String zipVersion = required(scanObject, "versionId");

    check(scanBucket.equals(properties.sourceBucket()), "Unexpected ZIP bucket");
    check(s3Key.equals("Scanned/" + releaseId + "/" + table.name() + ".zip"), "Unexpected ZIP key");

    var store = new ReleaseStore(s3);
    String checked = properties.scannedBucket();
    String extractedCsvKey = ReleaseStore.extractedCsvKey(releaseId, table.name());

    long stageStart = startStage("check-zip-scan", releaseId, dataset, context);
        if (!store.isClean(scanBucket, s3Key, zipVersion)) {
            throw new IllegalStateException("ZIP scan is pending");
    }
    completeStage("check-zip-scan", stageStart, releaseId, dataset, context);

        stageStart = startStage("check-zip-version", releaseId, dataset, context);
        check(zipVersion.equals(store.version(scanBucket, s3Key)), "ZIP version changed after scan");
        completeStage("check-zip-version", stageStart, releaseId, dataset, context);

    // Check schema version
    check(
        input.path(SCHEMA_VERSION_FIELD).isInt()
            && input.path(SCHEMA_VERSION_FIELD).intValue() == 1,
        "Unsupported ExtractRequested event version");

    String schemaVersion = table.version();
    var expectedReceipt =
        new ReceiptIdentity(
            releaseId, table.name(), s3Key, zipVersion, extractedCsvKey, schemaVersion);

    // Check for existing extract receipt
    stageStart = startStage("check-extract-receipt", releaseId, dataset, context);
        var extractReceipt = store.receipt(scanBucket, releaseId, table.name(), EXTRACT_FIELD);
        completeStage("check-extract-receipt", stageStart, releaseId, dataset, context);
        if (extractReceipt != null) {
            stageStart = startStage("verify-extract-receipt", releaseId, dataset, context);
            verifyReceipt(store, checked, extractReceipt, expectedReceipt);
            completeStage("verify-extract-receipt", stageStart, releaseId, dataset, context);
      return CSV_SCAN_STATUS;
    }

    GetObjectRequest request =
        GetObjectRequest.builder().bucket(scanBucket).key(s3Key).versionId(zipVersion).build();

    stageStart = startStage("open-zip", releaseId, dataset, context);
        try (var zip = new MeasuredZipInputStream(s3.getObject(request))) {
      completeStage("open-zip", stageStart, releaseId, dataset, context);
            return extractZip(zip, table, expectedReceipt, store, scanBucket, checked, context);
        } catch (ZipException e) {
            throw reject("MALFORMED_ZIP", e, scanBucket, s3Key, zipVersion, releaseId, dataset);
    } catch (CsvHeaderException e) {
      throw reject("HEADER_MISMATCH", e, scanBucket, s3Key, zipVersion, releaseId, dataset);
        } catch (MissingPrimaryCsvException e) {
            throw reject(CSV_NOT_FOUND, e, scanBucket, s3Key, zipVersion, releaseId, dataset);
        } catch (ArchiveValidationException e) {
            throw reject("ARCHIVE_INVALID", e, scanBucket, s3Key, zipVersion, releaseId, dataset);
    }
  }

  private String extractZip(
      MeasuredZipInputStream zip, DatasetCatalog.Table table, ReceiptIdentity expected,
                              ReleaseStore store, String scanBucket, String checked, Context context) throws Exception {
        var names = new HashSet<String>();
        long max = properties.maxExtractedBytes();
    var limited = new LimitedArchiveInputStream(zip, max);
        ZipEntry zipEntry;
        long locateStart = startStage("locate-primary-csv", expected.releaseId(), expected.dataset(), context);

    while ((zipEntry = zip.getNextEntry()) != null) {
      limited.startEntry(zipEntry);
            String name = zipEntry.getName();
            validateEntry(zipEntry, table.name(), names);

      if (name.equals(table.name() + ".csv")) {
        completeStage("locate-primary-csv", locateStart, expected.releaseId(), expected.dataset(), context);
                long stageStart = startStage("validate-csv-header", expected.releaseId(), expected.dataset(), context);
                var csvInput = new PushbackInputStream(limited, MAX_CSV_HEADER_BYTES);
                byte[] header = readHeader(csvInput);
                List<String> extractionHeaders = validateHeaders(header, table.header());
                csvInput.unread(header);
                completeStage("validate-csv-header", stageStart, expected.releaseId(), expected.dataset(), context);

        stageStart = startStage("upload-primary-csv", expected.releaseId(), expected.dataset(), context);
        StreamingS3.Uploaded result =
            new StreamingS3(s3, context::getRemainingTimeInMillis)
                .putIdempotent(csvInput, checked, expected.csvKey(), max,
                                () -> {
                                    long drainStart = startStage(
                                            "validate-remaining-entries", expected.releaseId(), expected.dataset(), context);
                                    drainRemainingEntries(zip, limited, table.name(), names, context);
                                    completeStage("validate-remaining-entries", drainStart,
                                            expected.releaseId(), expected.dataset(), context);
                                });
                completeStage("upload-primary-csv", stageStart, expected.releaseId(), expected.dataset(), context);

        var receiptData = buildUnzipReceiptData(expected, result, names.size());
        var headers = receiptData.putArray("csvHeaders");
        extractionHeaders.forEach(headers::add);
        LOG.info(
            "Extraction receipt prepared",
            Map.of(
                RELEASE_ID_FIELD,
                expected.releaseId(),
                DATASET_FIELD,
                expected.dataset(),
                "csv_key",
                result.key()));

        stageStart = startStage("save-extract-receipt", expected.releaseId(), expected.dataset(), context);
                saveExtractReceipt(store, scanBucket, checked, receiptData, result, expected);
                completeStage("save-extract-receipt", stageStart, expected.releaseId(), expected.dataset(), context);

        LOG.info(
            "Extraction completed",
            Map.of(RELEASE_ID_FIELD, expected.releaseId(), DATASET_FIELD, expected.dataset()));
        return CSV_SCAN_STATUS;
      } else {
        cleanUp(limited, context);
                LOG.info("Ancillary ZIP entry validated",
                        Map.of(RELEASE_ID_FIELD, expected.releaseId(),
                                DATASET_FIELD, expected.dataset(),
                                REQUEST_ID_FIELD, requestId(context),
                                "entry_count", names.size(),
                                "elapsed_ms", (System.nanoTime() - locateStart) / NANOS_PER_MILLI));
            }

      zip.closeEntry();
    }

    throw new MissingPrimaryCsvException(CSV_NOT_FOUND);
  }

  private void drainRemainingEntries(MeasuredZipInputStream zip, LimitedArchiveInputStream limited,
                                       String dataset, Set<String> names, Context context)
            throws IOException {
        ZipEntry entry;
        while ((entry = zip.getNextEntry()) != null) {
            limited.startEntry(entry);
            validateEntry(entry, dataset, names);
            check(!entry.getName().equals(dataset + ".csv"),
                    () -> new ArchiveValidationException("Duplicate primary CSV"));
            cleanUp(limited, context);
            zip.closeEntry();
        }
    }

    private static void validateEntry(ZipEntry entry, String dataset, Set<String> names)
            throws ArchiveValidationException {
        String name = entry.getName();
        check(names.size() < 100 && names.add(name),
                () -> new ArchiveValidationException("Too many or duplicate ZIP entries"));
        check(!entry.isDirectory() && !name.contains("/") && !name.contains("\\") && !name.contains(".."),
                () -> new ArchiveValidationException("Unexpected ZIP member path"));
        check(name.equals(dataset + ".csv") || name.matches(dataset + "_[a-z0-9_]+\\.csv"),
                () -> new ArchiveValidationException("Unexpected ZIP member"));
    }

    private PermanentExtractionException reject(
      String status, Exception failure, String bucket, String key, String version,
            String releaseId, String dataset) throws Exception {
        LOG.error("ZIP extraction failed",
                failure,
                Map.of(STATUS, status, RELEASE_ID_FIELD, releaseId, DATASET_FIELD, dataset));
        String quarantineKey = "quarantine/" + releaseId + "/" + dataset + "/"
                + sha(version.getBytes(StandardCharsets.UTF_8)) + ".zip";
        quarantine.copy(bucket, key, version, quarantineKey);
        return new PermanentExtractionException(status, failure);
    }

  private static String requestId(Context context) {
    String requestId = context.getAwsRequestId();
    return requestId == null ? "unknown" : requestId;
  }

  private static long startStage(String stage, String releaseId, String dataset, Context context) {
        LOG.info("Unzip stage started",
                Map.of("stage", stage, RELEASE_ID_FIELD, releaseId,
                        DATASET_FIELD, dataset, REQUEST_ID_FIELD, requestId(context)));
        return System.nanoTime();
    }

    private static void completeStage(String stage, long started, String releaseId,
                                      String dataset, Context context) {
        LOG.info("Unzip stage completed",
                Map.of("stage", stage, RELEASE_ID_FIELD, releaseId,
                        DATASET_FIELD, dataset, REQUEST_ID_FIELD, requestId(context),
                        "elapsed_ms", (System.nanoTime() - started) / NANOS_PER_MILLI));
    }

    private void saveExtractReceipt(ReleaseStore store, String scanBucket, String checked,
                                    ObjectNode receiptData, StreamingS3.Uploaded result,
                                    ReceiptIdentity expected) throws Exception {
        try {
            store.receiptOnce(scanBucket, expected.releaseId(), expected.dataset(), EXTRACT_FIELD, receiptData);
        } catch (S3Exception e) {
            if (!ReleaseStore.isPreconditionFailed(e)) throw e;
            var existingReceipt = store.receipt(scanBucket, expected.releaseId(), expected.dataset(), EXTRACT_FIELD);
            check(existingReceipt != null, "Extract receipt conflicted but is not readable");
            verifyReceipt(store, checked, existingReceipt, expected);
            check(required(existingReceipt, VERSION_FIELD).equals(result.version())
                            && required(existingReceipt, SHA256_FIELD).equals(result.sha256()),
                    "Existing extract receipt differs from uploaded CSV");
        }
    }

  private static ObjectNode buildUnzipReceiptData(
      ReceiptIdentity expected, StreamingS3.Uploaded result, int zipFileCount) {
    return JSON.createObjectNode()
        .put(RELEASE_ID_FIELD, expected.releaseId())
        .put(DATASET_FIELD, expected.dataset())
        .put("zipS3Key", expected.zipKey())
        .put("zipVersion", expected.zipVersion())
        .put("zipFileCount", zipFileCount)
        .put(SCHEMA_VERSION_FIELD, expected.schemaVersion())
        .put(STATUS, "EXTRACTED")
        .put(VERSION_FIELD, result.version())
        .put("key", result.key())
        .put(SHA256_FIELD, result.sha256())
        .put("extractedAt", Instant.now().toString());
  }

  private void verifyReceipt(
      ReleaseStore store, String bucket, JsonNode receipt, ReceiptIdentity expected)
      throws Exception {
    check(
        expected.releaseId().equals(required(receipt, RELEASE_ID_FIELD))
            && expected.dataset().equals(required(receipt, DATASET_FIELD))
            && expected.zipKey().equals(required(receipt, "zipS3Key"))
            && expected.zipVersion().equals(required(receipt, "zipVersion"))
            && expected.schemaVersion().equals(required(receipt, SCHEMA_VERSION_FIELD))
            && expected.csvKey().equals(required(receipt, "key"))
            && "EXTRACTED".equals(required(receipt, STATUS)),
        "Existing extract receipt does not match the scanned ZIP");
    String version = required(receipt, VERSION_FIELD);
    String checksum = required(receipt, SHA256_FIELD);
    check(
        version.equals(store.version(bucket, expected.csvKey())),
        "Extracted CSV version differs from its receipt");
    var evidence = store.read(bucket, StreamingS3.checksumKey(expected.csvKey()));
    check(
        evidence != null
            && bucket.equals(required(evidence, "bucket"))
            && expected.csvKey().equals(required(evidence, "key"))
            && version.equals(required(evidence, "versionId"))
            && checksum.equals(required(evidence, SHA256_FIELD)),
        "Extracted CSV checksum evidence differs from its receipt");
  }

  private List<String> validateHeaders(byte[] header, String expectedHeader)
      throws IOException, CsvHeaderException {
    try (CSVParser parser =
        CSVParser.parse(
            new String(header, StandardCharsets.UTF_8),
            CSVFormat.DEFAULT
                .builder()
                .setHeader()
                .setSkipHeaderRecord(true)
                .setIgnoreEmptyLines(false)
                .setTrim(false)
                .get())) {
      List<String> headers = parser.getHeaderNames();
      List<String> expectedFields = parseExpectedHeader(expectedHeader);
      check(
          headers.size() == expectedFields.size(),
          () -> new CsvHeaderException("Header size mismatch"));
      check(headers.equals(expectedFields), () -> new CsvHeaderException("Header mismatch"));

      return headers;
    }
  }

  private byte[] readHeader(InputStream in) throws IOException {
    var header = new ByteArrayOutputStream();
    int value;
    while ((value = in.read()) != -1) {
      header.write(value);
      if (header.size() > MAX_CSV_HEADER_BYTES) {
        throw new ZipException("CSV header exceeds maximum allowed size");
      }
      if (value == '\n') break;
    }
    return header.toByteArray();
  }

  private void cleanUp(InputStream in, Context context) throws IOException {
        byte[] buffer = new byte[64 * 1024];
    while (in.read(buffer) != -1) {
      check(context.getRemainingTimeInMillis() > 30000, "Unzip deadline");
    }
  }

  private List<String> parseExpectedHeader(String expectedHeader)
      throws IOException, CsvHeaderException {
    try (CSVParser parser = CSVParser.parse(expectedHeader, CSVFormat.DEFAULT)) {
      var records = parser.getRecords();
      check(
          records.size() == 1,
          () -> new CsvHeaderException("Expected schema header must contain one CSV record"));
      return records.getFirst().toList();
    }
  }

  private static final class MeasuredZipInputStream extends ZipInputStream {
    private MeasuredZipInputStream(InputStream input) {
            super(input);
        }

    private long compressedBytesRead() {
            return inf.getBytesRead();
        }
    }

    private static final class LimitedArchiveInputStream extends FilterInputStream {
        private final MeasuredZipInputStream zip;
        private final long expandedLimit;
        private long archiveExpanded;
        private long archiveCompressed;
        private long entryExpanded;
        private long entryCompressed;
        private int entryMethod;

        private LimitedArchiveInputStream(MeasuredZipInputStream zip, long expandedLimit) {
      super(zip);
      this.zip = zip;
      this.expandedLimit = expandedLimit;
        }

        private void startEntry(ZipEntry entry) {
            entryExpanded = 0;
            entryCompressed = 0;
            entryMethod = entry.getMethod();
    }

    @Override
    public int read() throws IOException {
      int value = in.read();
      if (value != -1) add(1);
      return value;
    }

    @Override
    public int read(byte @NonNull [] bytes, int offset, int length) throws IOException {
      int count = in.read(bytes, offset, length);
      if (count > 0) add(count);
      return count;
    }

    private void add(int count) throws ZipException {
            entryExpanded += count;
      archiveExpanded += count;
      if (archiveExpanded > expandedLimit)
                throw new ZipException("Expanded archive exceeds maximum allowed size");

            long compressed = entryMethod == ZipEntry.STORED
                    ? entryExpanded : zip.compressedBytesRead();
            archiveCompressed += compressed - entryCompressed;
            entryCompressed = compressed;
            if (entryExpanded > Math.max(1, entryCompressed) * MAX_ENTRY_COMPRESSION_RATIO)
                throw new ZipException("ZIP entry exceeds maximum compression ratio");
            if (archiveExpanded > Math.max(1, archiveCompressed) * MAX_ARCHIVE_COMPRESSION_RATIO)
                throw new ZipException("ZIP archive exceeds maximum compression ratio");
        }
    }
}
