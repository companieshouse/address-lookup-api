package uk.gov.companieshouse.addresslookup.lamda.shared.acquisition;

import com.amazonaws.services.lambda.runtime.Context;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import software.amazon.awssdk.services.s3.S3Client;
import uk.gov.companieshouse.addresslookup.lamda.shared.storage.ReleaseStore;
import uk.gov.companieshouse.release.model.ReleaseManifest;

import java.time.Instant;
import java.util.*;

import static uk.gov.companieshouse.addresslookup.lamda.shared.runtime.RuntimeSupport.*;

/**
 * Persists scan evidence before issuing commands so a retry can recover interrupted publication.
 * Each object is handled independently; publication readiness remains the import service's concern.
 */
public final class ScanResultsService {
  private static final String STATE_PREFIX = "scan-state/";
  private final S3Client s3;
  private final Commands commands;
  private final AcquisitionProperties properties;

  public ScanResultsService(S3Client s3, Commands commands, AcquisitionProperties properties) {
    this.s3 = s3;
    this.commands = commands;
    this.properties = properties;
    AcquisitionProperties.required(properties.scannedBucket(), "SCANNED_BUCKET");
  }

  public String scan(Map<String, Object> event, Context context) throws Exception {
    JsonNode input = JSON.valueToTree(event);
    if ("reconcile".equals(input.path("detail").path("action").asText())) {
      return reconcile(required(input.path("detail"), "releaseId"));
    }
    check(
        "aws.guardduty".equals(input.path("source").asText())
            || input.path("detail").path("scanResultDetails").hasNonNull("scanResultStatus"),
        "Unexpected scan event source");
    var evidence = ScanEvidence.from(input);
    var store = new ReleaseStore(s3);
    var previous = ScanState.read(store, properties.scannedBucket(), evidence);
    boolean alreadyPublished = previous.isAlreadyPublished(evidence);
    boolean statusChanged = previous.statusChanged(evidence);
    ScanState state = previous.record(evidence);
    state.write(store, properties.scannedBucket());
    if (alreadyPublished) return "DUPLICATE";
    if (statusChanged) {
      System.err.println(
          "Scan result changed for release=" + evidence.releaseId + " dataset=" + evidence.dataset);
    }
    publish(store, state);
    return state.status;
  }

  private String reconcile(String releaseId) throws Exception {
    UUID id = UUID.fromString(releaseId);
    var store = new ReleaseStore(s3);
    var result = JSON.createObjectNode();
    result.put("releaseId", id.toString());
    var files = result.putObject("scannedFiles");
    var missing = result.putArray("missingCommands");
    String prefix = "Scanned/" + id + "/";
    for (String key : store.keys(properties.scannedBucket(), prefix)) {
      Optional<ScanEvidence> parsed = ScanEvidence.fromKey(key, Instant.now().toString(), "CLEAN");
      if (parsed.isEmpty()) continue;
      ScanEvidence evidence = parsed.get();
      ScanState state = ScanState.read(store, properties.scannedBucket(), evidence);
      if ("SCAN_MISSING".equals(state.status)) {
        String tagStatus =
            store.scan(
                properties.scannedBucket(),
                evidence.s3Key,
                store.version(properties.scannedBucket(), evidence.s3Key));
        String recoveredStatus =
            ReleaseStore.CLEAN.equals(tagStatus)
                ? "CLEAN"
                : "THREATS_FOUND".equals(tagStatus) ? "INFECTED" : "SCAN_MISSING";
        if (!"SCAN_MISSING".equals(recoveredStatus)) {
          evidence =
              ScanEvidence.fromKey(evidence.s3Key, Instant.now().toString(), recoveredStatus)
                  .orElseThrow();
          state = state.record(evidence);
          state.write(store, properties.scannedBucket());
        }
      }
      ObjectNode summary =
          files
              .withObject(evidence.dataset)
              .withObject(evidence.fileType.toLowerCase(Locale.ROOT));
      summary.put("status", state.status);
      summary.put("scanned", !"SCAN_MISSING".equals(state.status));
      summary.put("commandPublished", state.commandPublished);
      if ("SCAN_MISSING".equals(state.status)) {
        state.write(store, properties.scannedBucket());
        continue;
      }
      if ("CLEAN".equals(state.status) && !state.commandPublished) {
        publish(store, state);
        ObjectNode gap = missing.addObject();
        gap.put("dataset", evidence.dataset);
        gap.put("fileType", evidence.fileType);
        gap.put(
            "action",
            "ZIP".equals(evidence.fileType)
                ? "publish_missing_unzip"
                : "publish_missing_import_ready");
        summary.put("commandPublished", true);
      }
    }
    // State is also examined: a scan event can arrive before an eventually-consistent list result.
    for (String key : store.keys(properties.scannedBucket(), STATE_PREFIX + id + "/")) {
      JsonNode node = store.read(properties.scannedBucket(), key);
      if (node == null || !"SCAN_MISSING".equals(node.path("status").asText())) continue;
      String dataset = required(node, "dataset");
      String type = required(node, "fileType").toLowerCase(Locale.ROOT);
      ObjectNode summary = files.withObject(dataset).withObject(type);
      summary.put("status", "SCAN_MISSING");
      summary.put("scanned", false);
      summary.put("commandPublished", false);
    }
    return JSON.writeValueAsString(result);
  }

  private void publish(ReleaseStore store, ScanState state) throws Exception {
    if ("INFECTED".equals(state.status)) {
      if (!state.notificationPublished) {
        commands.send(
            "INFECTION_DETECTED",
            Map.of(
                "releaseId", state.releaseId,
                "dataset", state.dataset,
                "fileType", state.fileType));
        state.notificationPublished = true;
        state.write(store, properties.scannedBucket());
      }
      return;
    }
    if (!"CLEAN".equals(state.status) || state.commandPublished) return;
    String planKey = planKey(store, state.releaseId);
    Map<String, String> command =
        "ZIP".equals(state.fileType)
            ? Map.of("planKey", planKey, "dataset", state.dataset, "s3Key", state.s3Key)
            : Map.of("planKey", planKey, "dataset", state.dataset, "csvS3Key", state.s3Key);
    commands.send("ZIP".equals(state.fileType) ? "UNZIP" : "IMPORT_READY", command);
    state.commandPublished = true;
    state.commandPublishedAt = Instant.now().toString();
    state.write(store, properties.scannedBucket());
  }

  private String planKey(ReleaseStore store, String releaseId) throws Exception {
    String matching = null;
    for (String key : store.keys(properties.sourceBucket(), "acquisitions/")) {
      JsonNode plan = store.read(properties.sourceBucket(), key);
      if (plan != null && releaseId.equals(plan.path("releaseId").asText())) {
        check(matching == null, "Multiple acquisition plans for scan release");
        ReleaseManifest.read(plan);
        matching = key;
      }
    }
    check(matching != null, "Acquisition plan is not available for scan release");
    return matching;
  }

  static final class ScanEvidence {
    final String releaseId, dataset, fileType, s3Key, completedAt, status;

    private ScanEvidence(
        String releaseId, String dataset, String fileType, String s3Key, String completedAt, String status) {
      this.releaseId = releaseId;
      this.dataset = dataset;
      this.fileType = fileType;
      this.s3Key = s3Key;
      this.completedAt = completedAt;
      this.status = status;
    }

    static ScanEvidence from(JsonNode event) throws Exception {
      JsonNode detail = event.path("detail");
      String key = null;
      boolean infected = false;
      for (JsonNode finding : detail.path("findings")) {
        JsonNode object = finding.path("resource").path("s3ObjectDetails");
        if (!object.hasNonNull("key")) object = finding.path("s3ObjectDetails");
        if (object.hasNonNull("key")) key = object.path("key").asText();
        infected |= highOrMedium(finding.path("severity"));
      }
      if (key == null) key = detail.path("s3ObjectDetails").path("key").asText(null);
      check(key != null && !key.isBlank(), "Scan event does not identify an object");
      String scanStatus = detail.path("scanResultDetails").path("scanResultStatus").asText();
      String status =
          infected || "THREATS_FOUND".equals(scanStatus)
              ? "INFECTED"
              : ("NO_THREATS_FOUND".equals(scanStatus) || scanStatus.isBlank())
                  ? "CLEAN"
                  : "INCONCLUSIVE";
      String completedAt =
          event.hasNonNull("time") ? event.path("time").asText() : Instant.now().toString();
      return fromKey(key, completedAt, status).orElseThrow(() -> new IllegalArgumentException("Unexpected scanned object key"));
    }

    static Optional<ScanEvidence> fromKey(String key, String completedAt, String status) {
      var matcher =
          java.util.regex.Pattern.compile(
                  "^Scanned/([0-9a-fA-F-]{36})/(add_(?:gb|isl)_(?:builtaddress|royalmailaddress))\\.(zip|csv)$")
              .matcher(key);
      if (!matcher.matches()) return Optional.empty();
      try {
        UUID release = UUID.fromString(matcher.group(1));
        String dataset = AcquisitionSupport.table(matcher.group(2)).name();
        return Optional.of(
            new ScanEvidence(
                release.toString(), dataset, matcher.group(3).toUpperCase(Locale.ROOT), key, completedAt, status));
      } catch (Exception invalid) {
        return Optional.empty();
      }
    }

    private static boolean highOrMedium(JsonNode severity) {
      if (severity.isNumber()) return severity.asDouble() >= 4.0;
      String value = severity.asText("").toUpperCase(Locale.ROOT);
      return "HIGH".equals(value) || "MEDIUM".equals(value);
    }
  }

  static final class ScanState {
    final String releaseId, dataset, fileType;
    String s3Key;
    String status, commandPublishedAt;
    boolean commandPublished, notificationPublished;
    final ArrayNode attempts;

    ScanState(ScanEvidence evidence, String status, boolean commandPublished,
        String commandPublishedAt, boolean notificationPublished, ArrayNode attempts) {
      this.releaseId = evidence.releaseId;
      this.dataset = evidence.dataset;
      this.fileType = evidence.fileType;
      this.s3Key = evidence.s3Key;
      this.status = status;
      this.commandPublished = commandPublished;
      this.commandPublishedAt = commandPublishedAt;
      this.notificationPublished = notificationPublished;
      this.attempts = attempts;
    }

    static ScanState read(ReleaseStore store, String bucket, ScanEvidence evidence) throws Exception {
      JsonNode node = store.read(bucket, key(evidence));
      if (node == null) return new ScanState(evidence, "SCAN_MISSING", false, null, false, JSON.createArrayNode());
      ArrayNode attempts = node.has("attempts") && node.path("attempts").isArray()
          ? (ArrayNode) node.path("attempts").deepCopy() : JSON.createArrayNode();
      var state = new ScanState(evidence, node.path("status").asText("SCAN_MISSING"),
          node.path("commandPublished").asBoolean(), node.path("commandPublishedAt").asText(null),
          node.path("notificationPublished").asBoolean(), attempts);
      state.s3Key = node.path("s3Key").asText(evidence.s3Key);
      return state;
    }

    ScanState record(ScanEvidence evidence) {
      boolean changed = !status.equals("SCAN_MISSING") && !status.equals(evidence.status);
      boolean differentObject = !s3Key.equals(evidence.s3Key);
      attempts.addObject().put("status", evidence.status).put("scanCompletedAt", evidence.completedAt);
      s3Key = evidence.s3Key;
      status = evidence.status;
      if (changed || differentObject) {
        commandPublished = false;
        commandPublishedAt = null;
        notificationPublished = false;
      }
      return this;
    }

    boolean isAlreadyPublished(ScanEvidence evidence) {
      return status.equals(evidence.status) && commandPublished && s3Key.equals(evidence.s3Key);
    }

    boolean statusChanged(ScanEvidence evidence) {
      return !status.equals("SCAN_MISSING") && !status.equals(evidence.status);
    }

    void write(ReleaseStore store, String bucket) {
      ObjectNode node = JSON.createObjectNode();
      node.put("releaseId", releaseId);
      node.put("dataset", dataset);
      node.put("fileType", fileType);
      node.put("s3Key", s3Key);
      node.put("status", status);
      node.put("scanStartedAt", attempts.path(0).path("scanCompletedAt").asText());
      node.put("scanCompletedAt", attempts.path(attempts.size() - 1).path("scanCompletedAt").asText());
      node.putArray("findings");
      node.set("attempts", attempts);
      node.put("commandPublished", commandPublished);
      if (commandPublishedAt != null) node.put("commandPublishedAt", commandPublishedAt);
      node.put("notificationPublished", notificationPublished);
      store.write(bucket, key(this), node);
    }

    private static String key(ScanEvidence evidence) {
      return STATE_PREFIX + evidence.releaseId + "/" + evidence.dataset + "/" + evidence.fileType + ".json";
    }

    private static String key(ScanState state) {
      return STATE_PREFIX + state.releaseId + "/" + state.dataset + "/" + state.fileType + ".json";
    }
  }
}
