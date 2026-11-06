package uk.gov.companieshouse.addresslookup.lambda.upload.service;

import static uk.gov.companieshouse.addresslookup.lambda.shared.runtime.RuntimeSupport.JSON;
import static uk.gov.companieshouse.addresslookup.lambda.shared.runtime.RuntimeSupport.detail;
import static uk.gov.companieshouse.addresslookup.lambda.shared.runtime.RuntimeSupport.required;

import com.amazonaws.services.lambda.runtime.Context;
import com.amazonaws.services.lambda.runtime.events.SQSBatchResponse;
import com.amazonaws.services.lambda.runtime.events.SQSEvent;
import com.fasterxml.jackson.databind.JsonNode;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.PushbackInputStream;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import org.apache.commons.csv.CSVFormat;
import org.apache.commons.csv.CSVParser;
import org.springframework.stereotype.Component;
import software.amazon.awssdk.services.s3.S3Client;
import software.amazon.awssdk.services.s3.model.GetObjectRequest;
import uk.gov.companieshouse.addresslookup.lambda.shared.acquisition.Commands;
import uk.gov.companieshouse.addresslookup.lambda.shared.storage.ReleaseStore;
import uk.gov.companieshouse.addresslookup.lambda.shared.util.KeyLayout;
import uk.gov.companieshouse.addresslookup.lambda.upload.runtime.UploadProperties;
import uk.gov.companieshouse.logging.Logger;
import uk.gov.companieshouse.logging.LoggerFactory;
import uk.gov.companieshouse.release.model.Dataset;
import uk.gov.companieshouse.release.model.DatasetCatalog;
import uk.gov.companieshouse.release.model.ReleaseManifest;

/**
 * Consumes the clean-scan events (ManifestReady, DatasetReady) from the upload queue. An event is
 * only a pointer: the exact object version is re-checked for a clean scan before it is read, and
 * the CSV is validated against the manifest recorded for its release before load.
 *
 * <p>Failures that redelivery cannot fix (business failures and malformed events) are published as
 * StepRejected so quarantine blocks the release; everything else is a runtime or infrastructure
 * failure that is retried and eventually dead-lettered.
 */
@Component
public final class UploadService {
  private static final Logger LOG = LoggerFactory.getLogger(UploadService.class.getName());
  private static final int MAX_HEADER_BYTES = 64 * 1024;
  private static final int MAX_MANIFEST_BYTES = 1024 * 1024;
  private static final String RELEASE_ID = "releaseId";
  private static final String DATASET = "dataset";
  private static final String PENDING = "PENDING";
  private static final String STEP = "upload";

  private final S3Client s3;
  private final UploadProperties properties;
  private final StagingLoader loader;
  private final Commands commands;

  public UploadService(
      S3Client s3, UploadProperties properties, StagingLoader loader, Commands commands) {
    this.s3 = s3;
    this.properties = properties;
    this.loader = loader;
    this.commands = commands;
  }

  /** Failed records are returned individually so only they are retried and eventually dead-lettered. */
  public SQSBatchResponse upload(SQSEvent event, Context context) {
    var failures = new ArrayList<SQSBatchResponse.BatchItemFailure>();
    for (var record : event.getRecords()) {
      try {
        String outcome = process(record.getBody());
        LOG.info("Upload processed", Map.of("outcome", outcome, "message_id", record.getMessageId()));
      } catch (PermanentFailureException e) {
        if (!reject(record, e)) {
          failures.add(new SQSBatchResponse.BatchItemFailure(record.getMessageId()));
        }
      } catch (InvalidEventException e) {
        if (!rejectInvalidEvent(record, e)) {
          failures.add(new SQSBatchResponse.BatchItemFailure(record.getMessageId()));
        }
      } catch (Exception e) {
        LOG.error("Upload failed", e, Map.of("message_id", record.getMessageId()));
        failures.add(new SQSBatchResponse.BatchItemFailure(record.getMessageId()));
      }
    }
    return new SQSBatchResponse(failures);
  }

  String process(String body) throws Exception {
    JsonNode input;
    String releaseId;
    JsonNode object;
    String bucket;
    String key;
    String version;
    KeyLayout.ObjectKey parsed;
    try {
      input = detail(body);
      require(input.path("schemaVersion").asInt() == 1, "Unsupported upload event version");
      releaseId = UUID.fromString(required(input, RELEASE_ID)).toString();
      object = input.path("object");
      bucket = required(object, "bucket");
      key = required(object, "key");
      version = required(object, "versionId");
      parsed = KeyLayout.parseObjectKey(key);
      require(
          UUID.fromString(parsed.releaseId()).toString().equals(releaseId),
          "Key does not match release");
    } catch (IllegalArgumentException | IOException e) {
      throw new InvalidEventException(e.getMessage(), e);
    }

    var store = new ReleaseStore(s3);
    if (store.isBlocked(bucket, releaseId)) {
      LOG.info("Upload skipped, release is blocked", Map.of("release_id", releaseId));
      return "SKIPPED_BLOCKED";
    }
    return switch (parsed.fileType()) {
      case MANIFEST -> processManifest(store, releaseId, bucket, key, version);
      case CSV -> processCsv(store, input, releaseId, bucket, key, version);
      default -> throw new InvalidEventException("Unsupported object type");
    };
  }

  private static void require(boolean condition, String message) {
    if (!condition) throw new InvalidEventException(message);
  }

  private String processManifest(
      ReleaseStore store, String releaseId, String bucket, String key, String version)
      throws Exception {
    require(bucket.equals(properties.ingestBucket()), "Unexpected manifest bucket");
    requireCleanScan(store, bucket, key, version);

    byte[] bytes = readBounded(bucket, key, version);
    ReleaseManifest manifest;
    try {
      manifest = ReleaseManifest.read(JSON.readTree(bytes));
    } catch (Exception e) {
      throw new PermanentFailureException(
          PermanentFailureException.MANIFEST_INVALID, "Manifest is invalid");
    }
    if (!manifest.id().toString().equals(releaseId)
        || !key.equals("acquisitions/" + manifest.target() + "/" + releaseId + ".json")) {
      throw new PermanentFailureException(
          PermanentFailureException.MANIFEST_INVALID, "Manifest does not match release");
    }
    loader.recordManifest(manifest);
    return "MANIFEST_RECORDED";
  }

  private String processCsv(
      ReleaseStore store, JsonNode input, String releaseId, String bucket, String key, String version)
      throws Exception {
    Dataset dataset;
    try {
      dataset = Dataset.named(required(input, DATASET));
    } catch (IllegalArgumentException e) {
      throw new InvalidEventException(e.getMessage(), e);
    }
    require(bucket.equals(properties.scannedBucket()), "Unexpected CSV bucket");
    require(key.equals(ReleaseStore.extractedCsvKey(releaseId, dataset.datasetName())), "Unexpected CSV key");
    requireCleanScan(store, bucket, key, version);

    var staged =
        loader
            .stagedManifest(UUID.fromString(releaseId), dataset)
            .orElseThrow(() -> new IllegalStateException("Release manifest not recorded yet; retry"));
    var table = DatasetCatalog.table(dataset);
    if (!table.version().equals(staged.schemaVersion())) {
      throw new PermanentFailureException(
          PermanentFailureException.SCHEMA_VERSION_MISMATCH,
          "Unsupported schemaVersion " + staged.schemaVersion() + " for " + dataset.datasetName());
    }

    var request = GetObjectRequest.builder().bucket(bucket).key(key).versionId(version).build();
    try (var in = new PushbackInputStream(s3.getObject(request), MAX_HEADER_BYTES + 2)) {
      validateHeader(in, table.columns().stream().map(DatasetCatalog.Column::name).toList());
      boolean loaded = loader.load(dataset, staged, key + "@" + version, in);
      LOG.info(
          "Upload loaded",
          Map.of("release_id", releaseId, "dataset", dataset.datasetName(), "loaded", loaded));
      return loaded ? "LOADED" : "ALREADY_LOADED";
    }
  }

  /** A pending scan is retried; a final unclean verdict is permanent. */
  private void requireCleanScan(ReleaseStore store, String bucket, String key, String version) {
    String status = store.scan(bucket, key, version);
    if (ReleaseStore.CLEAN.equals(status)) {
      require(version.equals(store.version(bucket, key)), "Object version changed after scan");
      return;
    }
    if (PENDING.equals(status)) throw new IllegalStateException("Scan not complete; retry");
    throw new PermanentFailureException(
        PermanentFailureException.SCAN_NOT_CLEAN, "GuardDuty scan " + status);
  }

  private byte[] readBounded(String bucket, String key, String version) throws IOException {
    var request = GetObjectRequest.builder().bucket(bucket).key(key).versionId(version).build();
    try (var in = s3.getObject(request)) {
      byte[] bytes = in.readNBytes(MAX_MANIFEST_BYTES + 1);
      if (bytes.length > MAX_MANIFEST_BYTES) {
        throw new IOException("Manifest exceeds 1 MiB");
      }
      return bytes;
    }
  }

  /** Publishes StepRejected for quarantine. Returns false if that failed so the record is retried. */
  private boolean reject(SQSEvent.SQSMessage record, PermanentFailureException failure) {
    try {
      JsonNode input = detail(record.getBody());
      JsonNode object = input.path("object");
      Map<String, Object> payload = new LinkedHashMap<>();
      payload.put("schemaVersion", 1);
      payload.put(RELEASE_ID, required(input, RELEASE_ID));
      if (input.hasNonNull(DATASET)) payload.put(DATASET, input.get(DATASET).asText());
      payload.put("object", JSON.convertValue(object, Map.class));
      payload.put("step", STEP);
      payload.put("code", failure.code());
      payload.put("message", failure.getMessage());
      commands.sendIngestion("StepRejected", payload);
      LOG.error(
          "Upload rejected",
          Map.of("code", failure.code(), "message_id", record.getMessageId()));
      return true;
    } catch (Exception e) {
      LOG.error("Could not publish upload rejection", e, Map.of("message_id", record.getMessageId()));
      return false;
    }
  }

  /**
   * Malformed or inconsistent events can never succeed on redelivery. With a usable release id the
   * release is rejected for quarantine; without one nothing can be blocked, so the record is
   * logged and acknowledged instead of being retried into the dead-letter queue.
   */
  private boolean rejectInvalidEvent(SQSEvent.SQSMessage record, Exception cause) {
    if (releaseIdOf(record.getBody()).isEmpty()) {
      LOG.error(
          "Upload event dropped, no usable release id",
          cause,
          Map.of("message_id", record.getMessageId()));
      return true;
    }
    return reject(
        record,
        new PermanentFailureException(PermanentFailureException.INVALID_EVENT, "Upload event is invalid"));
  }

  private static Optional<String> releaseIdOf(String body) {
    try {
      return Optional.of(UUID.fromString(required(detail(body), RELEASE_ID)).toString());
    } catch (Exception e) {
      return Optional.empty();
    }
  }

  /** Line terminators are LF, CRLF or a lone CR; a UTF-8 BOM before the header is tolerated. */
  private static void validateHeader(PushbackInputStream in, List<String> expected)
      throws IOException {
    var line = new ByteArrayOutputStream();
    int value;
    while ((value = in.read()) != -1) {
      line.write(value);
      if (line.size() > MAX_HEADER_BYTES) {
        throw mismatch("CSV header too large");
      }
      if (value == '\n') break;
      if (value == '\r') {
        int next = in.read();
        if (next == '\n') line.write(next);
        else if (next != -1) in.unread(next);
        break;
      }
    }
    byte[] header = line.toByteArray();
    String text = new String(header, StandardCharsets.UTF_8);
    if (text.startsWith("\uFEFF")) text = text.substring(1);
    try (CSVParser parser =
        CSVParser.parse(
            text, CSVFormat.DEFAULT.builder().setHeader().setSkipHeaderRecord(true).get())) {
      if (!parser.getHeaderNames().equals(expected)) {
        throw mismatch("CSV header does not match schema");
      }
    }
    in.unread(header);
  }

  private static PermanentFailureException mismatch(String message) {
    return new PermanentFailureException(PermanentFailureException.HEADER_MISMATCH, message);
  }
}
