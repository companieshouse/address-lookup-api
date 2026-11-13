package uk.gov.companieshouse.addresslookup.lambda.unzip.service;

import static uk.gov.companieshouse.addresslookup.lambda.shared.acquisition.AcquisitionSupport.table;
import static uk.gov.companieshouse.addresslookup.lambda.shared.runtime.RuntimeSupport.JSON;
import static uk.gov.companieshouse.addresslookup.lambda.shared.runtime.RuntimeSupport.check;
import static uk.gov.companieshouse.addresslookup.lambda.shared.runtime.RuntimeSupport.detail;
import static uk.gov.companieshouse.addresslookup.lambda.shared.runtime.RuntimeSupport.required;

import com.amazonaws.services.lambda.runtime.Context;
import com.fasterxml.jackson.databind.JsonNode;

import org.apache.commons.csv.CSVFormat;
import org.apache.commons.csv.CSVParser;
import org.jspecify.annotations.NonNull;
import org.springframework.stereotype.Component;

import software.amazon.awssdk.services.s3.S3Client;
import software.amazon.awssdk.services.s3.model.GetObjectRequest;

import uk.gov.companieshouse.addresslookup.lambda.shared.acquisition.AcquisitionProperties;
import uk.gov.companieshouse.addresslookup.lambda.shared.acquisition.Commands;
import uk.gov.companieshouse.addresslookup.lambda.shared.exception.PermanentFailureException;
import uk.gov.companieshouse.addresslookup.lambda.shared.storage.ReleaseStore;
import uk.gov.companieshouse.addresslookup.lambda.shared.storage.StreamingS3;
import uk.gov.companieshouse.addresslookup.lambda.shared.util.KeyLayout;
import uk.gov.companieshouse.logging.Logger;
import uk.gov.companieshouse.logging.LoggerFactory;
import uk.gov.companieshouse.release.model.Dataset;
import uk.gov.companieshouse.release.model.DatasetCatalog;

import java.io.ByteArrayOutputStream;
import java.io.FilterInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.PushbackInputStream;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.zip.ZipEntry;
import java.util.zip.ZipException;
import java.util.zip.ZipInputStream;

/** Red-zone extraction reads only the exact clean ZIP version. */
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
    private static final String LOG_RELEASE_ID = "release_id";
    private static final String REQUEST_ID_FIELD = "request_id";
    private static final String SCHEMA_VERSION_FIELD = "schemaVersion";
    private static final String CSV_SCAN_STATUS = "CSV_SCAN";
    private static final String CSV_NOT_FOUND = "CSV_NOT_FOUND";
    private static final String MALFORMED_ZIP = "MALFORMED_ZIP";
    private static final String HEADER_MISMATCH = "HEADER_MISMATCH";
    private static final String ARCHIVE_INVALID = "ARCHIVE_INVALID";
    private static final String STATUS = "status";
    private static final String VERSION_ID_FIELD = "versionId";
    private static final String DATASETS_FIELD = "datasets";
    private static final String ZIP_VERSION_FIELD = "zipVersion";
    private static final String CSV_KEY_FIELD = "csvKey";
    private static final String ZIP_KEY_FIELD = "zipKey";
    private static final Logger LOG = LoggerFactory.getLogger(ExtractionService.class.getName());

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
        AcquisitionProperties.required(properties.controlBucket(), "CONTROL_BUCKET");
        AcquisitionProperties.required(properties.landingBucket(), "LANDING_BUCKET");
        this.quarantine = new ZipQuarantineStore(s3, properties.maxZipBytes());
    }

    public String unzip(Map<String, Object> event, Context context) throws Exception {
        // Validate input
        JsonNode input = detail(event);
        String dataset = required(input, DATASET_FIELD);
        check(dataset.matches("[a-z][a-z0-9_]*"), "Invalid dataset");
        var table = table(dataset);

        JsonNode scanObject = input.path("object");
        String scanBucket = required(scanObject, "bucket");
        String s3Key = required(scanObject, "key");
        String zipVersion = required(scanObject, VERSION_ID_FIELD);
        var zipObject = KeyLayout.parseLandingObject(s3Key);
        check(zipObject.fileType() == KeyLayout.LandingFileType.ZIP, "Expected ZIP key");
        check(zipObject.dataset() == Dataset.named(dataset), "ZIP dataset mismatch");
        String releaseId =
                zipObject.packageVersion().packageId()
                        + "-"
                        + zipObject.packageVersion().versionId();
        LOG.info(
                "Extraction started",
                Map.of(
                        "request_id",
                        requestId(context),
                        RELEASE_ID_FIELD,
                        releaseId,
                        DATASET_FIELD,
                        dataset));

        check(scanBucket.equals(properties.landingBucket()), "Unexpected ZIP bucket");
        check(
                s3Key.equals(
                        KeyLayout.downloadZipKey(
                                zipObject.packageVersion(), Dataset.named(table.name()))),
                "Unexpected ZIP key");

        var store = new ReleaseStore(s3);
        String checked = properties.landingBucket();

        long stageStart = startStage("check-zip-scan", releaseId, dataset, context);
        if (!store.isClean(scanBucket, s3Key, zipVersion)) {
            throw new IllegalStateException("ZIP scan is pending");
        }
        completeStage("check-zip-scan", stageStart, releaseId, dataset, context);

        stageStart = startStage("check-zip-version", releaseId, dataset, context);
        check(
                zipVersion.equals(store.version(scanBucket, s3Key)),
                "ZIP version changed after scan");
        completeStage("check-zip-version", stageStart, releaseId, dataset, context);

        // Check schema version
        check(
                input.path(SCHEMA_VERSION_FIELD).isInt()
                        && input.path(SCHEMA_VERSION_FIELD).intValue() == 1,
                "Unsupported ExtractRequested event version");

        String csvKey =
                KeyLayout.extractedCsvKey(
                        zipObject.packageVersion(),
                        Dataset.named(table.name()),
                        table.name() + ".csv");
        if (alreadyExtracted(
                store, zipObject.packageVersion(), dataset, s3Key, zipVersion, csvKey)) {
            recordExtractionCursor(
                    store, zipObject.packageVersion(), dataset, s3Key, zipVersion, csvKey);
            commands.send(CSV_SCAN_STATUS, dataset);
            return CSV_SCAN_STATUS;
        }

        GetObjectRequest request =
                GetObjectRequest.builder()
                        .bucket(scanBucket)
                        .key(s3Key)
                        .versionId(zipVersion)
                        .build();

        stageStart = startStage("open-zip", releaseId, dataset, context);
        try (var zip = new MeasuredZipInputStream(s3.getObject(request))) {
            completeStage("open-zip", stageStart, releaseId, dataset, context);
            return extractZip(
                    zip, table, zipObject.packageVersion(), checked, context, s3Key, zipVersion);
        } catch (ZipException e) {
            logExtractionFailure(MALFORMED_ZIP, e, releaseId, dataset, s3Key);
            throw permanentFailure(MALFORMED_ZIP, e, releaseId, dataset);
        } catch (CsvHeaderException e) {
            logExtractionFailure(HEADER_MISMATCH, e, releaseId, dataset, s3Key);
            throw permanentFailure(HEADER_MISMATCH, e, releaseId, dataset);
        } catch (MissingPrimaryCsvException e) {
            logExtractionFailure(CSV_NOT_FOUND, e, releaseId, dataset, s3Key);
            throw permanentFailure(CSV_NOT_FOUND, e, releaseId, dataset);
        } catch (ArchiveValidationException e) {
            logExtractionFailure(ARCHIVE_INVALID, e, releaseId, dataset, s3Key);
            throw permanentFailure(ARCHIVE_INVALID, e, releaseId, dataset);
        }
    }

    private String extractZip(
            MeasuredZipInputStream zip,
            DatasetCatalog.Table table,
            KeyLayout.PackageVersion packageVersion,
            String checked,
            Context context,
            String zipKey,
            String zipVersion)
            throws Exception {
        var store = new ReleaseStore(s3);
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
                String csvKey =
                        KeyLayout.extractedCsvKey(
                                packageVersion, Dataset.named(table.name()), table.name() + ".csv");
                StreamingS3.Uploaded result =
                        new StreamingS3(s3, context::getRemainingTimeInMillis)
                                .putOverwrite(csvInput, checked, csvKey, max,
                                () -> {
                                    long drainStart = startStage(
                                            "validate-remaining-entries", expected.releaseId(), expected.dataset(), context);
                                    drainRemainingEntries(zip, limited, table.name(), names, context);
                                    completeStage("validate-remaining-entries", drainStart,
                                            expected.releaseId(), expected.dataset(), context);
                                });
                completeStage("upload-primary-csv", stageStart, expected.releaseId(), expected.dataset(), context);

                recordUnpackState(
                        store, checked, packageVersion, table.name(), zipKey, zipVersion, result);
                recordExtractionCursor(
                        store, packageVersion, table.name(), zipKey, zipVersion, result.key());

                String releaseId = packageVersion.packageId() + "-" + packageVersion.versionId();
                LOG.info(
                        "Extraction completed",
                        Map.of(
                                RELEASE_ID_FIELD,
                                releaseId,
                                DATASET_FIELD,
                                table.name(),
                                "csv_key",
                                result.key(),
                                "header_count",
                                extractionHeaders.size(),
                                "zip_file_count",
                                names.size()));
                commands.send(CSV_SCAN_STATUS, table.name());
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

        throw new MissingPrimaryCsvException("Primary CSV " + table.name() + ".csv is missing");
    }

    private static PermanentFailureException permanentFailure(
            String reasonCode, Exception cause, String releaseId, String dataset) {
        return PermanentFailureException.builder()
                .description(cause.getMessage())
                .cause(cause)
                .dataset(Dataset.named(dataset))
                .reasonCode(reasonCode)
                .releaseId(releaseId)
                .build();
    }

    private boolean alreadyExtracted(
            ReleaseStore store,
            KeyLayout.PackageVersion packageVersion,
            String dataset,
            String zipKey,
            String zipVersion,
            String csvKey)
            throws Exception {
        JsonNode state =
                store.read(properties.landingBucket(), KeyLayout.unpackStateKey(packageVersion));
        JsonNode extracted = state == null ? null : state.path(DATASETS_FIELD).path(dataset);
        if (extracted == null
                || !extracted.isObject()
                || !zipVersion.equals(required(extracted, ZIP_VERSION_FIELD))
                || !csvKey.equals(required(extracted, CSV_KEY_FIELD))
                || !zipKey.equals(required(extracted, ZIP_KEY_FIELD))
                || !store.exists(properties.landingBucket(), csvKey)) {
            return false;
        }
        String csvVersion = required(extracted, "csvVersion");
        return csvVersion.equals(store.version(properties.landingBucket(), csvKey));
    }

    private void recordUnpackState(
            ReleaseStore store,
            String bucket,
            KeyLayout.PackageVersion packageVersion,
            String dataset,
            String zipKey,
            String zipVersion,
            StreamingS3.Uploaded upload)
            throws java.io.IOException {
        String stateKey = KeyLayout.unpackStateKey(packageVersion);
        store.updateState(
                bucket,
                stateKey,
                current -> {
                    current.put("packageId", packageVersion.packageId());
                    current.put(VERSION_ID_FIELD, packageVersion.versionId());
                    current.put(
                            "feed",
                            packageVersion.feed().name().toLowerCase(java.util.Locale.ROOT));
                    JsonNode datasets = current.get(DATASETS_FIELD);
                    if (datasets == null) datasets = current.putObject(DATASETS_FIELD);
                    check(datasets.isObject(), "Unpack state datasets must be an object");
                    ((com.fasterxml.jackson.databind.node.ObjectNode) datasets)
                            .set(
                                    dataset,
                                    JSON.createObjectNode()
                                            .put(STATUS, "EXTRACTED")
                                            .put(ZIP_KEY_FIELD, zipKey)
                                            .put(ZIP_VERSION_FIELD, zipVersion)
                                            .put(CSV_KEY_FIELD, upload.key())
                                            .put("csvVersion", upload.version())
                                            .put("sha256", upload.sha256())
                                            .put("updatedAt", Instant.now().toString()));
                    return current;
                });
    }

    private void recordExtractionCursor(
            ReleaseStore store,
            KeyLayout.PackageVersion packageVersion,
            String dataset,
            String zipKey,
            String zipVersion,
            String csvKey)
            throws java.io.IOException {
        store.updateState(
                properties.controlBucket(),
                KeyLayout.extractionCursorKey(packageVersion.packageId()),
                current -> {
                    current.put("packageId", packageVersion.packageId());
                    current.put(
                            "feed",
                            packageVersion.feed().name().toLowerCase(java.util.Locale.ROOT));
                    current.put(VERSION_ID_FIELD, packageVersion.versionId());
                    current.put(DATASET_FIELD, dataset);
                    current.put(ZIP_KEY_FIELD, zipKey);
                    current.put(ZIP_VERSION_FIELD, zipVersion);
                    current.put(CSV_KEY_FIELD, csvKey);
                    current.put("updatedAt", Instant.now().toString());
                    return current;
                });
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
            String reasonCode, Exception failure, String bucket, String key, String version,
            String releaseId, String dataset, String s3Key) throws Exception {
        LOG.error(
                "ZIP extraction failed",
                failure,
                Map.of(
                        "outcome",
                        "PERMANENT_FAILURE",
                        "reason_code",
                        reasonCode,
                        LOG_RELEASE_ID,
                        releaseId,
                        DATASET_FIELD,
                        dataset,
                        "s3_key",
                        s3Key));
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

  private static ObjectNode buildUnzipReceiptData(ReceiptIdentity expected,
                                                  StreamingS3.Uploaded result, int zipFileCount) {
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

  private void verifyReceipt(ReleaseStore store, String bucket, JsonNode receipt,
                             ReceiptIdentity expected) throws Exception {
        check(expected.releaseId().equals(required(receipt, RELEASE_ID_FIELD))
                        && expected.dataset().equals(required(receipt, DATASET_FIELD))
                        && expected.zipKey().equals(required(receipt, "zipS3Key"))
                        && expected.zipVersion().equals(required(receipt, "zipVersion"))
                        && expected.schemaVersion().equals(required(receipt, SCHEMA_VERSION_FIELD))
                        && expected.csvKey().equals(required(receipt, "key"))
                        && "EXTRACTED".equals(required(receipt, STATUS)),
                "Existing extract receipt does not match the scanned ZIP");
        String version = required(receipt, VERSION_FIELD);
        String checksum = required(receipt, SHA256_FIELD);
        check(version.equals(store.version(bucket, expected.csvKey())),
                "Extracted CSV version differs from its receipt");
        var evidence = store.read(bucket, StreamingS3.checksumKey(expected.csvKey()));
        check(evidence != null
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

    private void cleanUp(InputStream in, Context context)
            throws IOException {
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
                    () ->
                            new CsvHeaderException(
                                    "Expected schema header must contain one CSV record"));
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
