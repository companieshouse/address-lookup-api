package uk.gov.companieshouse.addresslookup.lambda.unzip.service;

import static uk.gov.companieshouse.addresslookup.lambda.shared.runtime.RuntimeSupport.*;
import static uk.gov.companieshouse.addresslookup.lambda.shared.acquisition.AcquisitionSupport.table;

import com.amazonaws.services.lambda.runtime.Context;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import software.amazon.awssdk.core.sync.RequestBody;
import software.amazon.awssdk.services.s3.S3Client;
import software.amazon.awssdk.services.s3.model.GetObjectRequest;
import software.amazon.awssdk.services.s3.model.ListObjectsV2Request;
import software.amazon.awssdk.services.s3.model.PutObjectRequest;
import uk.gov.companieshouse.addresslookup.lambda.shared.acquisition.AcquisitionProperties;
import uk.gov.companieshouse.addresslookup.lambda.shared.acquisition.ExtractionProperties;
import uk.gov.companieshouse.addresslookup.lambda.shared.storage.ReleaseStore;
import uk.gov.companieshouse.addresslookup.lambda.shared.storage.StreamingS3;
import uk.gov.companieshouse.logging.Logger;
import uk.gov.companieshouse.logging.LoggerFactory;

/** FULL versions are resumed from Landing state; no extraction cursor or receipt is used. */
final class FullVersionExtraction {
  private static final Logger LOG = LoggerFactory.getLogger(FullVersionExtraction.class.getName());
  private static final long FILE_START_MARGIN_MILLIS = 120_000;
  private static final long MAX_SUMMARY_BYTES = 16L * 1024 * 1024;
  private static final String UNPACKED = "UNPACKED";
  private static final String PENDING = "PENDING";
  private static final String COMPLETE = "complete";
  private static final String NEEDS_ATTENTION = "NEEDS_ATTENTION";
  private static final String IN_PROGRESS = "IN_PROGRESS";
  private static final String VERSION_ID = "versionId";
  private static final String FEATURE_TYPE = "featureType";
  private static final String STATUS = "status";
  private static final String OUTPUT_KEYS = "outputKeys";
  private static final String FILES = "files";
  private static final String ORDER_SUMMARY = "ORDER_SUMMARY";
  private static final String ORDER_SUMMARY_SUFFIX = "_orderSummary.json";
  private final S3Client s3;
  private final AcquisitionProperties acquisition;
  private final ExtractionProperties extraction;
  private final ExtractionComponent zipExtractor;

  FullVersionExtraction(S3Client s3, AcquisitionProperties acquisition,
                        ExtractionProperties extraction, ExtractionComponent zipExtractor) {
    this.s3 = s3;
    this.acquisition = acquisition;
    this.extraction = extraction;
    this.zipExtractor = zipExtractor;
  }

  String extract(FullExtractionRequest request, Context context) throws Exception {
    check(extraction != null && extraction.full() != null, "Missing FULL feed configuration");
    var feed = extraction.full();
    String packageId = AcquisitionProperties.required(feed.packageId(), "FULL_PACKAGE_ID");
    String control = prefix(AcquisitionProperties.required(feed.controlPrefix(), "FULL_CONTROL_PREFIX"));
    String landing = prefix(feed.landingPrefix());

    check(!control.equals(landing), "Control and Landing prefixes must differ");
    check(packageId.equals(request.packageId()), "Unexpected FULL package");

    String versionPrefix = landing + "full/" + packageId + "/" + request.versionId() + "/";
    String bucket = acquisition.sourceBucket();
    var store = new ReleaseStore(s3);
    JsonNode manifest = loadAndValidateManifest(store, bucket, versionPrefix, request);

    String stateKey = versionPrefix + "manifest/unpack_state.json";
    State state = loadOrInitaliseState(store, bucket, versionPrefix, stateKey, manifest);
    List<InputFile> files = getFilesFromState(state.value(), versionPrefix);
    var entries = state.value().path(FILES);

    if (state.value().path(COMPLETE).asBoolean()) {
      LOG.info("FULL version already unpacked", Map.of("version", request.versionId()));
      return "COMPLETE";
    }

    for (InputFile file : files) {
      String key = file.key();
      String status = required(entries.path(key), STATUS);

      if (UNPACKED.equals(status)) {
        check(entries.path(key).path(OUTPUT_KEYS).isArray()
                && !entries.path(key).path(OUTPUT_KEYS).isEmpty(),
            "Unpacked file has no output keys");
        continue;
      }

      check(PENDING.equals(status), "Unknown unpack status");
      if (context.getRemainingTimeInMillis() <= FILE_START_MARGIN_MILLIS) return IN_PROGRESS;

      String versionId = file.versionId();
      String scan = store.scan(bucket, key, versionId);

      if (PENDING.equals(scan)) return IN_PROGRESS;

      if (!ReleaseStore.CLEAN.equals(scan)) {
        LOG.error("FULL file scan requires attention",
            Map.of("key", key, "scan_status", scan));
        return NEEDS_ATTENTION;
      }

      check(versionId.equals(store.version(bucket, key)), "FULL file changed after scan");
      var outputs = new ArrayList<String>();

      try (var input = s3.getObject(GetObjectRequest.builder()
          .bucket(bucket).key(key).versionId(versionId).build())) {
        String outputPrefix = versionPrefix + "downloads/" + file.featureType() + "/";
        if ("ZIP".equals(file.type())) {
          zipExtractor.extractFullZip(input, file.featureType(), outputPrefix,
              bucket, context, outputs);
        } else {
          String output = outputPrefix + key.substring(versionPrefix.length());
          new StreamingS3(s3, context::getRemainingTimeInMillis)
              .putIdempotent(input, bucket, output, MAX_SUMMARY_BYTES);
          outputs.add(output);
        }
      }

      var entry = (ObjectNode) entries.path(key);
      entry.put(STATUS, UNPACKED);
      var outputKeys = entry.putArray(OUTPUT_KEYS);
      outputs.forEach(outputKeys::add);

      state = saveState(bucket, stateKey, state);
      entries = state.value().path(FILES);
    }
    state.value().put(COMPLETE, true);
    saveState(bucket, stateKey, state);
    return "COMPLETE";
  }

  private static JsonNode loadAndValidateManifest(ReleaseStore store, String bucket,
                                                  String versionPrefix, FullExtractionRequest request) throws Exception {
    JsonNode manifest = store.read(bucket, versionPrefix + "manifest.json");
    check(manifest != null, "FULL version manifest not found");
    check("FULL".equals(required(manifest, "mode"))
            && request.packageId().equals(required(manifest, "packageId"))
            && request.versionId().equals(required(manifest, VERSION_ID))
            && manifest.has("previous") && manifest.get("previous").isNull(),
        "FULL version manifest does not match request");

    return manifest;
  }

  private State loadOrInitaliseState(ReleaseStore store, String bucket, String versionPrefix,
                                     String stateKey, JsonNode manifest) throws Exception {
    String manifestSha = sha(manifest.toString().getBytes(StandardCharsets.UTF_8));
    State state = readState(bucket, stateKey);

    if (state == null) {
      List<InputFile> files = getDownloadFiles(store, bucket, versionPrefix);
      var initial = JSON.createObjectNode().put("manifestSha256", manifestSha).put(COMPLETE, false);
      var entries = initial.putObject(FILES);

      for (InputFile file : files) {
        entries.putObject(file.key())
                .put(VERSION_ID, file.versionId())
                .put(FEATURE_TYPE, file.featureType())
                .put("type", file.type())
                .put(STATUS, PENDING)
                .putArray(OUTPUT_KEYS);
      }

      s3.putObject(PutObjectRequest.builder().bucket(bucket).key(stateKey)
                      .ifNoneMatch("*").contentType("application/json").build(),
              RequestBody.fromString(initial.toString()));
      state = requireState(bucket, stateKey);
    }

    check(manifestSha.equals(required(state.value(), "manifestSha256")),
            "FULL unpack state does not match version manifest");
    check(state.value().path(COMPLETE).isBoolean(), "Invalid FULL completion flag");

    return state;
  }

  private static List<InputFile> getFilesFromState(JsonNode state, String versionPrefix)
      throws Exception {
    JsonNode entries = state.path(FILES);
    check(entries.isObject() && !entries.isEmpty(), "FULL unpack state has no files");

    var files = new ArrayList<InputFile>();
    for (var field : entries.properties()) {
      String key = field.getKey();
      JsonNode entry = field.getValue();
      String versionId = required(entry, VERSION_ID);
      String featureType = required(entry, FEATURE_TYPE);
      String type = required(entry, "type");

      String filename = switch (type) {
        case "ZIP" -> featureType + ".zip";
        case ORDER_SUMMARY -> featureType + ORDER_SUMMARY_SUFFIX;
        default -> throw new IllegalArgumentException("Unexpected FULL file type: " + type);
      };

      check(!versionId.isBlank() && key.equals(versionPrefix + filename), "Invalid FULL file entry");
      table(featureType);

      String status = required(entry, STATUS);
      check(PENDING.equals(status) || UNPACKED.equals(status), "Unknown unpacked status");
      check(entry.path(OUTPUT_KEYS).isArray(), "Invalid FULL output keys");

      if (UNPACKED.equals(status)) {
        check(!entry.path(OUTPUT_KEYS).isEmpty(), "Unpacked file has no output keys");
      }

      files.add(new InputFile(key, versionId, featureType, type));
    }

    if (state.path(COMPLETE).asBoolean()) {
      check(files.stream().allMatch(file ->
                      UNPACKED.equals(entries.path(file.key()).path(STATUS).asText())),
          "FULL unpack state is marked complete but has pending files");
    }

    return files;
  }

  private static String prefix(String configured) {
    check(configured != null, "Missing feed prefix");
    if (configured.isEmpty()) return "";
    check(!configured.startsWith("/") && !configured.contains("..")
        && !configured.contains("//") && configured.matches("[a-zA-Z0-9_/-]+"),
        "Invalid feed prefix");
    return configured.endsWith("/") ? configured : configured + "/";
  }

  private record InputFile(String key, String versionId, String featureType, String type) {}

  private static List<InputFile> getDownloadFiles(
          ReleaseStore store, String bucket, String versionPrefix) throws Exception {
    var files = new ArrayList<InputFile>();

    for (String key : store.keys(bucket, versionPrefix)) {
      String name = key.substring(versionPrefix.length());

      if (name.equals("manifest.json")
              || name.startsWith("manifest/")
              || name.startsWith("downloads/")) {
        continue;
      }

      check(!name.contains("/"), "Unexpected FULL input: " + key);

      String featureType;
      String type;
      if (name.endsWith(ORDER_SUMMARY_SUFFIX)) {
        featureType = name.substring(0, name.length() - ORDER_SUMMARY_SUFFIX.length());
        type = ORDER_SUMMARY;
      } else if (name.endsWith(".zip")) {
        featureType = name.substring(0, name.length() - ".zip".length());
        type = "ZIP";
      } else {
        throw new IllegalArgumentException("Unexpected FULL input: " + key);
      }

      table(featureType);
      files.add(new InputFile(key, store.version(bucket, key), featureType, type));
    }

    check(!files.isEmpty(), "FULL version has no input files");
    return files;
  }

  private static void validateFile(JsonNode file, String prefix, HashSet<String> seen)
      throws Exception {
    String key = required(file, "key");
    String versionId = required(file, VERSION_ID);
    String feature = required(file, FEATURE_TYPE);
    String type = required(file, "type");
    check(!versionId.isBlank() && feature.matches("[a-z][a-z0-9_]*"),
        "Invalid FULL file reference");
    table(feature);
    String filename = switch (type) {
      case "ZIP" -> feature + ".zip";
      case ORDER_SUMMARY -> feature + ORDER_SUMMARY_SUFFIX;
      default -> throw new IllegalArgumentException("Unexpected FULL file type: " + type);
    };
    check(key.equals(prefix + filename) && seen.add(key),
        "Unexpected or duplicate FULL file");
  }

  private record State(ObjectNode value, String stateTag) {}

  private State readState(String bucket, String key) throws IOException {
    var objects = s3.listObjectsV2(ListObjectsV2Request.builder()
        .bucket(bucket).prefix(key).maxKeys(1).build());
    if (objects.contents().stream().noneMatch(object -> key.equals(object.key()))) return null;
    try (var input = s3.getObject(GetObjectRequest.builder().bucket(bucket).key(key).build())) {
      byte[] bytes = input.readNBytes(1024 * 1024 + 1);
      check(bytes.length <= 1024 * 1024, "FULL unpack state exceeds 1 MiB");
      JsonNode value = JSON.readTree(bytes);
      check(value instanceof ObjectNode, "Invalid FULL unpack state");
      String stateTag = input.response().eTag();
      check(stateTag != null, "FULL unpack state has no ETag");
      return new State((ObjectNode) value, stateTag);
    }
  }

  private State saveState(String bucket, String key, State state)
      throws IOException {
    s3.putObject(PutObjectRequest.builder().bucket(bucket).key(key).ifMatch(state.stateTag())
            .contentType("application/json").build(),
        RequestBody.fromString(state.value().toString()));
    return requireState(bucket, key);
  }

  private State requireState(String bucket, String key) throws IOException {
    State state = readState(bucket, key);
    if (state == null) {
      throw new IllegalStateException("FULL unpack state is unreadable after write: " + key);
    }
    return state;
  }
}
