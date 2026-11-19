package uk.gov.companieshouse.addresslookup.lambda.unzip.service;

import static uk.gov.companieshouse.addresslookup.lambda.shared.acquisition.AcquisitionSupport.*;
import static uk.gov.companieshouse.addresslookup.lambda.shared.runtime.RuntimeSupport.*;

import com.amazonaws.services.lambda.runtime.Context;
import com.fasterxml.jackson.databind.JsonNode;
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
import org.jspecify.annotations.NonNull;
import org.springframework.stereotype.Component;
import software.amazon.awssdk.services.s3.S3Client;
import software.amazon.awssdk.services.s3.model.GetObjectRequest;
import uk.gov.companieshouse.addresslookup.lambda.shared.acquisition.AcquisitionProperties;
import uk.gov.companieshouse.addresslookup.lambda.shared.acquisition.Commands;
import uk.gov.companieshouse.addresslookup.lambda.shared.storage.ReleaseStore;
import uk.gov.companieshouse.addresslookup.lambda.shared.storage.StreamingS3;
import uk.gov.companieshouse.addresslookup.lambda.shared.util.KeyLayout;
import uk.gov.companieshouse.logging.Logger;
import uk.gov.companieshouse.logging.LoggerFactory;
import uk.gov.companieshouse.release.model.Dataset;
import uk.gov.companieshouse.release.model.DatasetCatalog;

/**
 * Red-zone extraction reads only the exact clean ZIP version.
 */
@Component
public final class ExtractionService {
  private final S3Client s3;
  private final AcquisitionProperties properties;
  private final Commands commands;
  private static final int MAX_CSV_HEADER_BYTES = 64 * 1024;
  private static final String DATASET_FIELD = "dataset";
  private static final String RELEASE_ID_FIELD = "releaseId";
  private static final String SCHEMA_VERSION_FIELD = "schemaVersion";
  private static final String EXTRACT_FIELD = "extract";
  private static final String CSV_SCAN_STATUS = "CSV_SCAN";
  private static final String CSV_NOT_FOUND = "CSV_NOT_FOUND";
  private static final String STATUS = "status";
  private static final Logger LOG = LoggerFactory.getLogger(ExtractionService.class.getName());

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
    AcquisitionProperties.required(properties.controlBucket(), "CONTROL_BUCKET");
    AcquisitionProperties.required(properties.landingBucket(), "LANDING_BUCKET");
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
    String zipVersion = required(scanObject, "versionId");
    var zipObject = KeyLayout.parseLandingObject(s3Key);
    check(zipObject.fileType() == KeyLayout.LandingFileType.ZIP, "Expected ZIP key");
    check(zipObject.dataset() == Dataset.named(dataset), "ZIP dataset mismatch");
    String releaseId =
        zipObject.packageVersion().packageId() + "-" + zipObject.packageVersion().versionId();
    LOG.info(
        "Extraction started",
        Map.of(
            "request_id", requestId(context), RELEASE_ID_FIELD, releaseId, DATASET_FIELD, dataset));

    check(scanBucket.equals(properties.landingBucket()), "Unexpected ZIP bucket");
    check(
        s3Key.equals(
            KeyLayout.downloadZipKey(zipObject.packageVersion(), Dataset.named(table.name()))),
        "Unexpected ZIP key");

    var store = new ReleaseStore(s3);
    String checked = properties.landingBucket();

    if (!store.isClean(scanBucket, s3Key, zipVersion)) {
      return "WAITING_FOR_ZIP_SCAN";
    }
    check(zipVersion.equals(store.version(scanBucket, s3Key)), "ZIP version changed after scan");

    // Check schema version
    check(
        input.path(SCHEMA_VERSION_FIELD).isInt()
            && input.path(SCHEMA_VERSION_FIELD).intValue() == 1,
        "Unsupported ExtractRequested event version");

    String csvKey =
        KeyLayout.extractedCsvKey(
            zipObject.packageVersion(), Dataset.named(table.name()), table.name() + ".csv");
    if (alreadyExtracted(
        store, zipObject.packageVersion(), dataset, s3Key, zipVersion, csvKey)) {
      recordExtractionCursor(
          store, zipObject.packageVersion(), dataset, s3Key, zipVersion, csvKey);
      commands.send(CSV_SCAN_STATUS, dataset);
      return CSV_SCAN_STATUS;
    }

    GetObjectRequest request =
        GetObjectRequest.builder().bucket(scanBucket).key(s3Key).versionId(zipVersion).build();

    try (var zip = new ZipInputStream(s3.getObject(request))) {
      return extractZip(
          zip, table, zipObject.packageVersion(), checked, context, s3Key, zipVersion);
    } catch (ZipException e) {
      logExtractionFailure("MALFORMED_ZIP", e, releaseId, dataset);
      return "MALFORMED_ZIP";
    } catch (CsvHeaderException e) {
      logExtractionFailure("HEADER_MISMATCH", e, releaseId, dataset);
      return "HEADER_MISMATCH";
    } catch (MissingPrimaryCsvException e) {
      logExtractionFailure(CSV_NOT_FOUND, e, releaseId, dataset);
      return CSV_NOT_FOUND;
    } catch (ArchiveValidationException e) {
      logExtractionFailure("ARCHIVE_INVALID", e, releaseId, dataset);
      return "ARCHIVE_INVALID";
    }
  }

  private String extractZip(
      ZipInputStream zip,
      DatasetCatalog.Table table,
      KeyLayout.PackageVersion packageVersion,
      String checked,
      Context context,
      String zipKey,
      String zipVersion)
      throws Exception {
    var store = new ReleaseStore(s3);
    var names = new HashSet<String>();
    long[] expandedBytes = {0};
    var csvInput = new PushbackInputStream(zip, MAX_CSV_HEADER_BYTES);
    long max = properties.maxExtractedBytes();
    ZipEntry zipEntry;

    while ((zipEntry = zip.getNextEntry()) != null) {
      String name = zipEntry.getName();
      check(
          names.size() < 100 && names.add(name),
          () -> new ArchiveValidationException("Too many or duplicate ZIP entries"));
      check(
          !name.contains("/") && !name.contains("\\") && !name.contains(".."),
          () -> new ArchiveValidationException("Unexpected ZIP member path"));

      if (name.equals(table.name() + ".csv")) {
        byte[] header = readHeader(csvInput);
        List<String> extractionHeaders = validateHeaders(header, table.header());
        csvInput.unread(header);

        var csv = new CountingInputStream(csvInput, expandedBytes, max);
        String csvKey =
            KeyLayout.extractedCsvKey(
                packageVersion, Dataset.named(table.name()), table.name() + ".csv");
        StreamingS3.Uploaded result =
            new StreamingS3(s3, context::getRemainingTimeInMillis)
                .putOverwrite(csv, checked, csvKey, max);

        recordUnpackState(
            store,
            checked,
            packageVersion,
            table.name(),
            zipKey,
            zipVersion,
            result);
        recordExtractionCursor(
            store,
            packageVersion,
            table.name(),
            zipKey,
            zipVersion,
            result.key());

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
        check(
            name.startsWith(table.name() + "_") && name.endsWith(".csv"),
            () -> new ArchiveValidationException("Unexpected ZIP member"));
        cleanUp(zip, expandedBytes, max, context);
      }

      zip.closeEntry();
    }

    return new MissingPrimaryCsvException(CSV_NOT_FOUND).getMessage();
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
    JsonNode extracted = state == null ? null : state.path("datasets").path(dataset);
    if (extracted == null
        || !extracted.isObject()
        || !zipVersion.equals(required(extracted, "zipVersion"))
        || !csvKey.equals(required(extracted, "csvKey"))
        || !zipKey.equals(required(extracted, "zipKey"))
        || !store.exists(properties.landingBucket(), csvKey)) {
      return false;
    }
    String csvVersion = required(extracted, "csvVersion");
    if (!csvVersion.equals(store.version(properties.landingBucket(), csvKey))) return false;
    return store.isClean(properties.landingBucket(), csvKey, csvVersion);
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
          current.put("versionId", packageVersion.versionId());
          current.put("feed", packageVersion.feed().name().toLowerCase(java.util.Locale.ROOT));
          JsonNode datasets = current.get("datasets");
          if (datasets == null) datasets = current.putObject("datasets");
          check(datasets.isObject(), "Unpack state datasets must be an object");
          ((com.fasterxml.jackson.databind.node.ObjectNode) datasets)
              .set(
                  dataset,
                  JSON.createObjectNode()
                      .put("status", "EXTRACTED")
                      .put("zipKey", zipKey)
                      .put("zipVersion", zipVersion)
                      .put("csvKey", upload.key())
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
          current.put("feed", packageVersion.feed().name().toLowerCase(java.util.Locale.ROOT));
          current.put("versionId", packageVersion.versionId());
          current.put("dataset", dataset);
          current.put("zipKey", zipKey);
          current.put("zipVersion", zipVersion);
          current.put("csvKey", csvKey);
          current.put("updatedAt", Instant.now().toString());
          return current;
        });
  }

  private static void logExtractionFailure(
      String status, Exception failure, String releaseId, String dataset) {
    LOG.error(
        "ZIP extraction failed",
        failure,
        Map.of(STATUS, status, RELEASE_ID_FIELD, releaseId, DATASET_FIELD, dataset));
  }

  private static String requestId(Context context) {
    String requestId = context.getAwsRequestId();
    return requestId == null ? "unknown" : requestId;
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

  private void cleanUp(InputStream in, long[] total, long limit, Context context)
      throws IOException {
    byte[] buffer = new byte[64 * 1024];
    int bytesRead;
    while ((bytesRead = in.read(buffer)) != -1) {
      total[0] += bytesRead;
      check(
          total[0] <= limit,
          () -> new ZipException("Expanded archive exceeds maximum allowed size"));
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
