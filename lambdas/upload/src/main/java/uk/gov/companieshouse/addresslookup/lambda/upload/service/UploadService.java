package uk.gov.companieshouse.addresslookup.lambda.upload.service;

import static uk.gov.companieshouse.addresslookup.lambda.shared.runtime.RuntimeSupport.JSON;
import static uk.gov.companieshouse.addresslookup.lambda.shared.runtime.RuntimeSupport.detail;
import static uk.gov.companieshouse.addresslookup.lambda.shared.runtime.RuntimeSupport.required;

import com.amazonaws.services.lambda.runtime.events.SQSBatchResponse;
import com.amazonaws.services.lambda.runtime.events.SQSEvent;
import com.fasterxml.jackson.databind.JsonNode;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.PushbackInputStream;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
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
  private static final String RELEASE_ID_LOG = "release_id";
  private static final String DATASET = "dataset";
  private static final String OBJECT = "object";
  private static final String MESSAGE_ID = "message_id";
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
  public SQSBatchResponse upload(SQSEvent event) {
    var failures = new ArrayList<SQSBatchResponse.BatchItemFailure>();
    for (var sqsRecord : event.getRecords()) {
      try {
        String outcome = process(sqsRecord.getBody());
        LOG.info(
            "Upload processed", Map.of("outcome", outcome, MESSAGE_ID, sqsRecord.getMessageId()));
      } catch (PermanentFailureException e) {
        if (!reject(sqsRecord, e)) {
          failures.add(new SQSBatchResponse.BatchItemFailure(sqsRecord.getMessageId()));
        }
      } catch (InvalidEventException e) {
        if (!rejectInvalidEvent(sqsRecord, e)) {
          failures.add(new SQSBatchResponse.BatchItemFailure(sqsRecord.getMessageId()));
        }
      } catch (Exception e) {
        LOG.error("Upload failed", e, Map.of(MESSAGE_ID, sqsRecord.getMessageId()));
        failures.add(new SQSBatchResponse.BatchItemFailure(sqsRecord.getMessageId()));
      }
    }
    return new SQSBatchResponse(failures);
  }

  String process(String body) throws Exception {
    JsonNode input;
    String releaseId;
    JsonNode objectNode;
    String bucket;
    String key;
    String version;
    KeyLayout.ObjectKey parsed;
    try {
      input = detail(body);
      require(input.path("schemaVersion").asInt() == 1, "Unsupported upload event version");
      releaseId = UUID.fromString(required(input, RELEASE_ID)).toString();
      objectNode = input.path(OBJECT);
      bucket = required(objectNode, "bucket");
      key = required(objectNode, "key");
      version = required(objectNode, "versionId");
      parsed = KeyLayout.parseObjectKey(key);
      require(
          UUID.fromString(parsed.releaseId()).toString().equals(releaseId),
          "Key does not match release");
    } catch (IllegalArgumentException | IOException e) {
      throw new InvalidEventException(e.getMessage(), e);
    }

    var store = new ReleaseStore(s3);
    if (isBlocked(store, releaseId)) {
      LOG.info("Upload skipped, release is blocked", Map.of(RELEASE_ID_LOG, releaseId));
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

  /**
   * The marker is looked up in the configured buckets, never the one named by the event, so a
   * release blocked by quarantine is skipped whichever bucket the marker was written to.
   */
  private boolean isBlocked(ReleaseStore store, String releaseId) throws Exception {
    for (String bucket :
        new LinkedHashSet<>(List.of(properties.ingestBucket(), properties.scannedBucket()))) {
      if (store.isBlocked(bucket, releaseId)) return true;
    }
    return false;
  }

  private static String superseded(String releaseId, String key) {
    LOG.info(
        "Upload skipped, object version superseded", Map.of(RELEASE_ID_LOG, releaseId, "key", key));
    return "SKIPPED_SUPERSEDED";
  }

  private String processManifest(
      ReleaseStore store, String releaseId, String bucket, String key, String version)
      throws Exception {
    require(bucket.equals(properties.ingestBucket()), "Unexpected manifest bucket");
    if (!isCurrentCleanVersion(store, bucket, key, version)) return superseded(releaseId, key);

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
    if (manifest.files().isEmpty()) {
      throw new PermanentFailureException(
          PermanentFailureException.MANIFEST_INVALID, "Manifest contains no datasets");
    }
    loader.recordManifest(manifest);
    return "MANIFEST_RECORDED";
  }

  private String processCsv(
      ReleaseStore store,
      JsonNode input,
      String releaseId,
      String bucket,
      String key,
      String version)
      throws Exception {
    Dataset dataset;
    try {
      dataset = Dataset.named(required(input, DATASET));
    } catch (IllegalArgumentException e) {
      throw new InvalidEventException(e.getMessage(), e);
    }
    require(bucket.equals(properties.scannedBucket()), "Unexpected CSV bucket");
    require(
        key.equals(ReleaseStore.extractedCsvKey(releaseId, dataset.datasetName())),
        "Unexpected CSV key");
    if (!isCurrentCleanVersion(store, bucket, key, version)) return superseded(releaseId, key);

    var id = UUID.fromString(releaseId);
    var staged = stagedManifestRow(id, dataset);
    var table = DatasetCatalog.table(dataset);
    if (!table.version().equals(staged.schemaVersion())) {
      throw new PermanentFailureException(
          PermanentFailureException.SCHEMA_VERSION_MISMATCH,
          "Unsupported schemaVersion " + staged.schemaVersion() + " for " + dataset.datasetName());
    }

    var request = GetObjectRequest.builder().bucket(bucket).key(key).versionId(version).build();
    var body = s3.getObject(request);
    try (var in = new PushbackInputStream(body, MAX_HEADER_BYTES + 2)) {
      boolean consumed = false;
      try {
        validateHeader(in, table.columns().stream().map(DatasetCatalog.Column::name).toList());
        boolean loaded = loader.load(dataset, staged, key + "@" + version, in);
        consumed = loaded;
        LOG.info(
            "Upload loaded",
            Map.of(RELEASE_ID_LOG, releaseId, DATASET, dataset.datasetName(), "loaded", loaded));
        return loaded ? "LOADED" : "ALREADY_LOADED";
      } finally {
        // Closing a partly read body would drain the rest of the CSV; abort it before close.
        if (!consumed) body.abort();
      }
    }
  }

  /**
   * A pending scan is retried; a final unclean verdict is permanent. A clean version that is no
   * longer current is reported as superseded: the newer version gets its own scan event, so this
   * one is acknowledged instead of blocking the release.
   */
  private StagingLoader.StagedManifest stagedManifestRow(UUID id, Dataset dataset) {
    var staged = loader.stagedManifest(id, dataset);
    if (staged.isPresent()) return staged.get();
    if (!loader.manifestRecorded(id)) {
      throw new IllegalStateException("Release manifest not recorded yet; retry");
    }
    // The manifest may have committed between the two queries; its rows are never deleted,
    // so a second miss is conclusive.
    return loader
        .stagedManifest(id, dataset)
        .orElseThrow(() ->
            new PermanentFailureException(
                PermanentFailureException.DATASET_NOT_IN_RELEASE,
                "Dataset " + dataset.datasetName() + " is not in the release manifest"));
  }

  private boolean isCurrentCleanVersion(
      ReleaseStore store, String bucket, String key, String version) {
    String status = store.scan(bucket, key, version);
    if (ReleaseStore.CLEAN.equals(status)) {
      return version.equals(store.version(bucket, key));
    }
    if (PENDING.equals(status)) throw new IllegalStateException("Scan not complete; retry");
    throw new PermanentFailureException(
        PermanentFailureException.SCAN_NOT_CLEAN, "GuardDuty scan " + status);
  }

  /** S3 read errors stay transient; an oversized manifest can never succeed, so it is permanent. */
  private byte[] readBounded(String bucket, String key, String version) throws IOException {
    var request = GetObjectRequest.builder().bucket(bucket).key(key).versionId(version).build();
    try (var in = s3.getObject(request)) {
      byte[] bytes = in.readNBytes(MAX_MANIFEST_BYTES + 1);
      if (bytes.length > MAX_MANIFEST_BYTES) {
        in.abort();
        throw new PermanentFailureException(
            PermanentFailureException.MANIFEST_INVALID, "Manifest exceeds 1 MiB");
      }
      return bytes;
    }
  }

  /** Publishes StepRejected for quarantine. Returns false if that failed so the record is retried. */
  private boolean reject(SQSEvent.SQSMessage sqsRecord, PermanentFailureException failure) {
    try {
      JsonNode input = detail(sqsRecord.getBody());
      JsonNode objectNode = input.path(OBJECT);
      Map<String, Object> payload = new LinkedHashMap<>();
      payload.put("schemaVersion", 1);
      payload.put(RELEASE_ID, required(input, RELEASE_ID));
      if (input.hasNonNull(DATASET)) payload.put(DATASET, input.get(DATASET).asText());
      if (objectNode.isObject()) payload.put(OBJECT, JSON.convertValue(objectNode, Map.class));
      payload.put("step", STEP);
      payload.put("code", failure.code());
      payload.put("message", failure.getMessage());
      commands.sendIngestion("StepRejected", payload);
      LOG.error(
          "Upload rejected", Map.of("code", failure.code(), MESSAGE_ID, sqsRecord.getMessageId()));
      return true;
    } catch (Exception e) {
      LOG.error(
          "Could not publish upload rejection", e, Map.of(MESSAGE_ID, sqsRecord.getMessageId()));
      return false;
    }
  }

  /**
   * Malformed or inconsistent events can never succeed on redelivery. With a usable release id the
   * release is rejected for quarantine; without one nothing can be blocked, so the record is
   * logged and acknowledged instead of being retried into the dead-letter queue.
   */
  private boolean rejectInvalidEvent(SQSEvent.SQSMessage sqsRecord, Exception cause) {
    if (releaseIdOf(sqsRecord.getBody()).isEmpty()) {
      LOG.error(
          "Upload event dropped, no usable release id",
          cause,
          Map.of(MESSAGE_ID, sqsRecord.getMessageId()));
      return true;
    }
    return reject(
        sqsRecord,
        new PermanentFailureException(
            PermanentFailureException.INVALID_EVENT, "Upload event is invalid"));
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
    boolean lineComplete = false;
    while (!lineComplete && (value = in.read()) != -1) {
      line.write(value);
      if (line.size() > MAX_HEADER_BYTES) {
        throw mismatch("CSV header too large");
      }
      if (value == '\n') lineComplete = true;
      if (value == '\r') {
        int next = in.read();
        if (next == '\n') line.write(next);
        else if (next != -1) in.unread(next);
        lineComplete = true;
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
    } catch (IOException | IllegalArgumentException e) {
      throw mismatch("CSV header is malformed");
    }
    in.unread(header);
  }

  private static PermanentFailureException mismatch(String message) {
    return new PermanentFailureException(PermanentFailureException.HEADER_MISMATCH, message);
  }
}
