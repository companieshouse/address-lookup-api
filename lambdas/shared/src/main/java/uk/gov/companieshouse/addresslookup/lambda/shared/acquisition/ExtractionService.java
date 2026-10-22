package uk.gov.companieshouse.addresslookup.lambda.shared.acquisition;

import com.amazonaws.services.lambda.runtime.Context;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.jspecify.annotations.NonNull;
import org.slf4j.LoggerFactory;
import software.amazon.awssdk.services.s3.S3Client;
import software.amazon.awssdk.services.s3.model.GetObjectRequest;
import uk.gov.companieshouse.addresslookup.lambda.shared.storage.ReleaseStore;
import uk.gov.companieshouse.addresslookup.lambda.shared.storage.StreamingS3;
import uk.gov.companieshouse.release.model.DatasetCatalog;

import java.io.*;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.zip.ZipEntry;
import java.util.zip.ZipException;
import java.util.zip.ZipInputStream;

import org.apache.commons.csv.CSVFormat;
import org.apache.commons.csv.CSVParser;
import software.amazon.awssdk.services.s3.model.S3Exception;

import static uk.gov.companieshouse.addresslookup.lambda.shared.acquisition.AcquisitionSupport.*;
import static uk.gov.companieshouse.addresslookup.lambda.shared.runtime.RuntimeSupport.*;

/**
 * Red-zone extraction reads only the exact clean ZIP version.
 */
public final class ExtractionService {
    private final S3Client s3;
    private final AcquisitionProperties properties;
    private final Commands commands;
    private static final int maxCsvHeaderBytes = 64 * 1024;
    private static final String datasetField = "dataset";
    private static final String releaseIdField = "releaseId";
    private static final String schemaVersionField = "schemaVersion";
    private static final String extractField = "extract";
    private static final String versionField = "version";
    private static final String sha256Field = "sha256";
    private static final String csvScanStatus = "CSV_SCAN";
    private static final org.slf4j.Logger LOGGER = LoggerFactory.getLogger(ExtractionService.class);

    private record ReceiptIdentity(String releaseId, String dataset, String zipKey,
                                   String zipVersion, String csvKey, String schemaVersion) {
    }

    static class CsvHeaderException extends Exception {
        CsvHeaderException(String msg) {
            super(msg);
        }
    }

    static class ArchiveValidationException extends Exception {
        ArchiveValidationException(String msg) {
            super(msg);
        }
    }

    static class MissingPrimaryCsvException extends Exception {
        MissingPrimaryCsvException(String msg) {
            super(msg);
        }
    }

    public ExtractionService(S3Client s3, Commands commands, AcquisitionProperties properties) {
        this.s3 = s3;
        this.properties = properties;
        this.commands = commands;
        AcquisitionProperties.required(properties.scannedBucket(), "SCANNED_BUCKET");
    }

    public String unzip(Map<String, Object> event, Context context) throws Exception {
        // Validate input
        JsonNode input = detail(event);
        var inputKeys = new HashSet<String>();
        input.fieldNames().forEachRemaining(inputKeys::add);
        LOGGER.info("UNZIP requestId={} eventKeys={} inputKeys={}",
                context.getAwsRequestId(), event.keySet(), inputKeys);
        String dataset = required(input, datasetField);
        check(dataset.matches("[a-z][a-z0-9_]*"), "Invalid dataset");
        var table = table(dataset);

        String releaseId = required(input, releaseIdField);
        JsonNode scanObject = input.path("object");
        String scanBucket = required(scanObject, "bucket");
        String s3Key = required(scanObject, "key");
        String zipVersion = required(scanObject, "versionId");

        check(scanBucket.equals(properties.sourceBucket()), "Unexpected ZIP bucket");
        check(s3Key.equals("Scanned/" + releaseId + "/" + table.name() + ".zip"),
                "Unexpected ZIP key");

        var store = new ReleaseStore(s3);
        String checked = properties.scannedBucket();
        String extractedCsvKey = ReleaseStore.extractedCsvKey(releaseId, table.name());

        if (!store.isClean(scanBucket, s3Key, zipVersion)) {
            return "WAITING_FOR_ZIP_SCAN";
        }
        check(zipVersion.equals(store.version(scanBucket, s3Key)), "ZIP version changed after scan");

        LOGGER.info("UNZIP STARTED - dataset={}\n", dataset);

        // Check schema version
        check(input.path(schemaVersionField).isInt()
                        && input.path(schemaVersionField).intValue() == 1,
                "Unsupported ExtractRequested event version");

        String schemaVersion = table.version();
        var expectedReceipt = new ReceiptIdentity(
                releaseId, table.name(), s3Key, zipVersion, extractedCsvKey, schemaVersion);

        // Check for existing extract receipt
        var extractReceipt = store.receipt(scanBucket, releaseId, table.name(), extractField);
        if (extractReceipt != null) {
            verifyReceipt(store, checked, extractReceipt, expectedReceipt);
            commands.send(csvScanStatus, table.name());
            return csvScanStatus;
        }

        GetObjectRequest request = GetObjectRequest.builder()
                .bucket(scanBucket)
                .key(s3Key)
                .versionId(zipVersion)
                .build();

        try (var zip = new ZipInputStream(s3.getObject(request))) {
            return extractZip(zip, table, expectedReceipt, store, scanBucket, checked, context);
        } catch (ZipException e) {
            LOGGER.error("UNZIP FAILURE - status=MALFORMED_ZIP message={}\n", e.getMessage());
            return "MALFORMED_ZIP";
        } catch (CsvHeaderException e) {
            LOGGER.error("UNZIP FAILURE - status=HEADER_MISMATCH message={}\n", e.getMessage());
            return "HEADER_MISMATCH";
        } catch (MissingPrimaryCsvException e) {
            LOGGER.error("UNZIP FAILURE - status=CSV_NOT_FOUND message={}\n", e.getMessage());
            return "CSV_NOT_FOUND";
        } catch (ArchiveValidationException e) {
            LOGGER.error("UNZIP FAILURE - status=ARCHIVE_INVALID message={}\n", e.getMessage());
            return "ARCHIVE_INVALID";
        }
    }

    private String extractZip(ZipInputStream zip, DatasetCatalog.Table table, ReceiptIdentity expected,
                              ReleaseStore store, String scanBucket, String checked, Context context) throws Exception {
        var names = new HashSet<String>();
        long[] expandedBytes = {0};
        var csvInput = new PushbackInputStream(zip, maxCsvHeaderBytes);
        long max = properties.maxExtractedBytes();
        ZipEntry zipEntry;

        while ((zipEntry = zip.getNextEntry()) != null) {
            String name = zipEntry.getName();
            check(names.size() < 100 && names.add(name),
                    () -> new ArchiveValidationException("Too many or duplicate ZIP entries"));
            check(!name.contains("/") && !name.contains("\\") && !name.contains(".."),
                    () -> new ArchiveValidationException("Unexpected ZIP member path"));

            if (name.equals(table.name() + ".csv")) {
                byte[] header = readHeader(csvInput);
                List<String> extractionHeaders = validateHeaders(header, table.header());
                csvInput.unread(header);

                var csv = new CountingInputStream(csvInput, expandedBytes, max);
                StreamingS3.Uploaded result = new StreamingS3(s3, context::getRemainingTimeInMillis)
                        .putIdempotent(csv, checked, expected.csvKey(), max);

                var receiptData = buildUnzipReceiptData(expected, result, names.size());
                var headers = receiptData.putArray("csvHeaders");
                for (String headerName : extractionHeaders) {
                    headers.add(headerName);
                }
                LOGGER.info("UNZIP - receiptData={}\n", receiptData);

                saveExtractReceipt(store, scanBucket, checked, receiptData, result, expected);
                commands.send(csvScanStatus, table.name());

                LOGGER.info("UNZIP - Completed\n");
                return csvScanStatus;
            } else {
                check(name.startsWith(table.name() + "_") && name.endsWith(".csv"),
                        () -> new ArchiveValidationException("Unexpected ZIP member"));
                cleanUp(zip, expandedBytes, max, context);
            }

            zip.closeEntry();
        }

        return new MissingPrimaryCsvException("CSV_NOT_FOUND").getMessage();
    }

    private void saveExtractReceipt(ReleaseStore store, String scanBucket, String checked,
                                    ObjectNode receiptData, StreamingS3.Uploaded result,
                                    ReceiptIdentity expected) throws Exception {
        try {
            store.receiptOnce(scanBucket, expected.releaseId(), expected.dataset(), extractField, receiptData);
        } catch (S3Exception e) {
            if (!ReleaseStore.isPreconditionFailed(e)) throw e;
            var existingReceipt = store.receipt(scanBucket, expected.releaseId(), expected.dataset(), extractField);
            check(existingReceipt != null, "Extract receipt conflicted but is not readable");
            verifyReceipt(store, checked, existingReceipt, expected);
            check(required(existingReceipt, versionField).equals(result.version())
                            && required(existingReceipt, sha256Field).equals(result.sha256()),
                    "Existing extract receipt differs from uploaded CSV");
        }
    }

  private static ObjectNode buildUnzipReceiptData(ReceiptIdentity expected,
                                                  StreamingS3.Uploaded result, int zipFileCount) {
      return JSON.createObjectNode()
              .put(releaseIdField, expected.releaseId())
              .put(datasetField, expected.dataset())
              .put("zipS3Key", expected.zipKey())
              .put("zipVersion", expected.zipVersion())
              .put("zipFileCount", zipFileCount)
              .put(schemaVersionField, expected.schemaVersion())
              .put("status", "EXTRACTED")
              .put(versionField, result.version())
              .put("key", result.key())
              .put(sha256Field, result.sha256())
              .put("extractedAt", Instant.now().toString());
  }

  private void verifyReceipt(ReleaseStore store, String bucket, JsonNode receipt,
                             ReceiptIdentity expected) throws Exception {
        check(expected.releaseId().equals(required(receipt, releaseIdField))
                        && expected.dataset().equals(required(receipt, datasetField))
                        && expected.zipKey().equals(required(receipt, "zipS3Key"))
                        && expected.zipVersion().equals(required(receipt, "zipVersion"))
                        && expected.schemaVersion().equals(required(receipt, schemaVersionField))
                        && expected.csvKey().equals(required(receipt, "key"))
                        && "EXTRACTED".equals(required(receipt, "status")),
                "Existing extract receipt does not match the scanned ZIP");
        String version = required(receipt, versionField);
        String checksum = required(receipt, sha256Field);
        check(version.equals(store.version(bucket, expected.csvKey())),
                "Extracted CSV version differs from its receipt");
        var evidence = store.read(bucket, StreamingS3.checksumKey(expected.csvKey()));
        check(evidence != null
                        && bucket.equals(required(evidence, "bucket"))
                        && expected.csvKey().equals(required(evidence, "key"))
                        && version.equals(required(evidence, "versionId"))
                        && checksum.equals(required(evidence, sha256Field)),
                "Extracted CSV checksum evidence differs from its receipt");
    }

    private List<String> validateHeaders(byte[] header, String expectedHeader)
            throws IOException, CsvHeaderException {
        try (CSVParser parser = CSVParser.parse(
                new String(header, StandardCharsets.UTF_8),
                CSVFormat.DEFAULT.builder().setHeader().setSkipHeaderRecord(true).setIgnoreEmptyLines(false)
                        .setTrim(false).get())) {
            List<String> headers = parser.getHeaderNames();
            List<String> expectedFields = parseExpectedHeader(expectedHeader);
            check(headers.size() == expectedFields.size(),
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
            if (header.size() > maxCsvHeaderBytes) {
                throw new ZipException("CSV header exceeds maximum allowed size");
            }
            if (value == '\n') break;
        }
        return header.toByteArray();
    }

    private void cleanUp(InputStream in, long[] total, long limit, Context context) throws IOException {
        byte[] buffer = new byte[64 * 1024];
        int bytesRead;
        while ((bytesRead = in.read(buffer)) != -1) {
            total[0] += bytesRead;
            check(total[0] <= limit, () -> new ZipException("Expanded archive exceeds maximum allowed size"));
            check(context.getRemainingTimeInMillis() > 30000, "Unzip deadline");
        }
    }

    private List<String> parseExpectedHeader(String expectedHeader)
            throws IOException, CsvHeaderException {
        try (CSVParser parser = CSVParser.parse(expectedHeader, CSVFormat.DEFAULT)) {
            var records = parser.getRecords();
            check(records.size() == 1,
                    () -> new CsvHeaderException("Expected schema header must contain one CSV record"));
            return records.getFirst().toList();
        }
    }

    private static final class CountingInputStream extends FilterInputStream {
        private final long[] total;
        private final long limit;

        private CountingInputStream(InputStream in, long[] total, long limit) {
            super(in);
            this.total = total;
            this.limit = limit;
        }

        @Override
        public int read() throws IOException {
            int value = super.read();
            if (value != -1) add(1);
            return value;
        }

        @Override
        public int read(byte @NonNull [] bytes, int offset, int length) throws IOException {
            int read = super.read(bytes, offset, length);
            if (read > 0) add(read);
            return read;
        }

        private void add(int bytes) throws IOException {
            total[0] += bytes;
            if (total[0] > limit) throw new ZipException("Expanded archive exceeds maximum allowed size");
        }
    }
}
