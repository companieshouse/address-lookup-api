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
    private final int MAX_CSV_HEADER_BYTES = 64 * 1024;
    private static final org.slf4j.Logger LOGGER = LoggerFactory.getLogger(ExtractionService.class);

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
        String dataset = required(input, "dataset");
        check(dataset.matches("[a-z][a-z0-9_]*"), "Invalid dataset");
        var table = table(dataset);

        String releaseId = required(input, "releaseId");
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
        LOGGER.info("UNZIP checked={}\n", checked);

        // Check schema version
        check(input.path("schemaVersion").isInt()
                        && input.path("schemaVersion").intValue() == 1,
                "Unsupported ExtractRequested event version");

        String schemaVersion = table.version();

        // Check for existing extract receipt
        var extractReceipt = store.receipt(scanBucket, releaseId, table.name(), "extract");
        String csvStatus = "CSV_SCAN";
        if (extractReceipt != null) {
            verifyReceipt(store, checked, extractReceipt, releaseId, table.name(),
                    s3Key, zipVersion, extractedCsvKey, schemaVersion);
            commands.send(csvStatus, table.name());
            return csvStatus;
        }

        GetObjectRequest request = GetObjectRequest.builder()
                .bucket(scanBucket)
                .key(s3Key)
                .versionId(zipVersion)
                .build();

        try (var zip = new ZipInputStream(s3.getObject(request))) {
            var names = new HashSet<String>();
            long[] expandedBytes = {0};
            var csvInput = new PushbackInputStream(zip, MAX_CSV_HEADER_BYTES);
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

                    var csv = new CountingInputStream(
                            csvInput,
                            expandedBytes,
                            max);
                    StreamingS3.Uploaded result = new StreamingS3(s3, context::getRemainingTimeInMillis)
                            .putIdempotent(csv, checked, extractedCsvKey, max);

                  var receiptData = buildUnzipReceiptData(releaseId, table.name(), s3Key, zipVersion, result, names.size(), schemaVersion);

                  var headers = receiptData.putArray("csvHeaders");
                    for (String headerName : extractionHeaders) {
                        headers.add(headerName);
                    }
                    LOGGER.info("UNZIP - receiptData={}\n", receiptData);

                    try {
                        store.receiptOnce(scanBucket, releaseId, table.name(), "extract", receiptData);
                    } catch (S3Exception e) {
                        if (!ReleaseStore.isPreconditionFailed(e)) throw e;
                        var existingReceipt = store.receipt(scanBucket, releaseId, table.name(), "extract");
                        check(existingReceipt != null, "Extract receipt conflicted but is not readable");
                        verifyReceipt(store, checked, existingReceipt, releaseId, table.name(), s3Key, zipVersion, extractedCsvKey, schemaVersion);
                        check(required(existingReceipt, "version").equals(result.version())
                                        && required(existingReceipt, "sha256").equals(result.sha256()),
                                "Existing extract receipt differs from uploaded CSV");
                    }
                    commands.send(csvStatus, table.name());

                    LOGGER.info("UNZIP - Completed\n");
                    return csvStatus;
                } else {
                    check(name.startsWith(table.name() + "_") && name.endsWith(".csv"),
                            () -> new ArchiveValidationException("Unexpected ZIP member"));
                    cleanUp(zip, expandedBytes, max, context);
                }

                zip.closeEntry();
            }

            return new MissingPrimaryCsvException("CSV_NOT_FOUND").getMessage();

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

  private static ObjectNode buildUnzipReceiptData(String releaseId, String dataset,
                                                  String s3Key, String zipVersion, StreamingS3.Uploaded result,
                                                  int zipFileCount, String schemaVersion) {
      return JSON.createObjectNode()
              .put("releaseId", releaseId)
              .put("dataset", dataset)
              .put("zipS3Key", s3Key)
              .put("zipVersion", zipVersion)
              .put("zipFileCount", zipFileCount)
              .put("schemaVersion", schemaVersion)
              .put("status", "EXTRACTED")
              .put("version", result.version())
              .put("key", result.key())
              .put("sha256", result.sha256())
              .put("extractedAt", Instant.now().toString());
  }

  private void verifyReceipt(ReleaseStore store, String bucket, JsonNode receipt, String id, String dataset,
                               String zipKey, String zipVersion, String csvKey, String schemaVersion) throws Exception {
        check(id.equals(required(receipt, "releaseId"))
                        && dataset.equals(required(receipt, "dataset"))
                        && zipKey.equals(required(receipt, "zipS3Key"))
                        && zipVersion.equals(required(receipt, "zipVersion"))
                        && schemaVersion.equals(required(receipt, "schemaVersion"))
                        && csvKey.equals(required(receipt, "key"))
                        && "EXTRACTED".equals(required(receipt, "status")),
                "Existing extract receipt does not match the scanned ZIP");
        String version = required(receipt, "version");
        String checksum = required(receipt, "sha256");
        check(version.equals(store.version(bucket, csvKey)),
                "Extracted CSV version differs from its receipt");
        var evidence = store.read(bucket, StreamingS3.checksumKey(csvKey));
        check(evidence != null
                        && bucket.equals(required(evidence, "bucket"))
                        && csvKey.equals(required(evidence, "key"))
                        && version.equals(required(evidence, "versionId"))
                        && checksum.equals(required(evidence, "sha256")),
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
            if (header.size() > MAX_CSV_HEADER_BYTES) {
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
        private long count;

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
            count += bytes;
            total[0] += bytes;
            if (total[0] > limit) throw new ZipException("Expanded archive exceeds maximum allowed size");
        }
    }
}
