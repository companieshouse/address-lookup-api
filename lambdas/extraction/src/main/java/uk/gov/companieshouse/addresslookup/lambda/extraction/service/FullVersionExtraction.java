package uk.gov.companieshouse.addresslookup.lambda.extraction.service;

import static uk.gov.companieshouse.addresslookup.lambda.extraction.service.ExtractionResult.Status.COMPLETE;
import static uk.gov.companieshouse.addresslookup.lambda.extraction.service.ExtractionResult.Status.IN_PROGRESS;
import static uk.gov.companieshouse.addresslookup.lambda.extraction.service.ExtractionResult.Status.NEEDS_ATTENTION;
import static uk.gov.companieshouse.addresslookup.lambda.shared.runtime.RuntimeSupport.*;
import static uk.gov.companieshouse.release.model.DatasetCatalog.extractionHeader;

import com.amazonaws.services.lambda.runtime.Context;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;
import java.util.zip.ZipException;
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
  private static final String STATE_COMPLETE = "complete";
  private static final String MEMBER_COMPLETE = "COMPLETE";
  private static final String MEMBER_RECOVERING = "RECOVERING";
  private static final String MEMBER_SKIP = "SKIP";
  private static final String ACTION = "action";
  private static final String BYTES = "bytes";
  private static final String OUTPUT_KEY = "outputKey";
  private static final String VERSION_ID = "versionId";
  private static final String SHA256 = "sha256";
  private static final String FEATURE_TYPE = "featureType";
  private static final String STATUS = "status";
  private static final String OUTPUT_KEYS = "outputKeys";
  private static final String SKIPPED = "skipped";
  private static final String MEMBERS = "members";
  private static final String FILES = "files";
  private static final String ORDER_SUMMARY = "ORDER_SUMMARY";
  private static final String ORDER_SUMMARY_SUFFIX = "_orderSummary.json";
  private static final String DOWNLOADS_PREFIX = "downloads/";
  private final S3Client s3;
  private final AcquisitionProperties acquisition;
  private final ExtractionProperties extraction;
  private final ExtractionComponent zipExtractor;

  FullVersionExtraction(
      S3Client s3,
      AcquisitionProperties acquisition,
      ExtractionProperties extraction,
      ExtractionComponent zipExtractor) {
    this.s3 = s3;
    this.acquisition = acquisition;
    this.extraction = extraction;
    this.zipExtractor = zipExtractor;
  }

  ExtractionResult extract(FullExtractionRequest request, Context context)
      throws IOException,
          NoSuchAlgorithmException,
          StreamingS3.ChecksumMismatchException,
          StreamingS3.ExistingObjectVerificationException {
    var feed = extraction == null ? null : extraction.full();
    if (feed == null) {
      throw new IllegalArgumentException("Missing FULL feed configuration");
    }
    String packageId = AcquisitionProperties.required(feed.packageId(), "FULL_PACKAGE_ID");
    String control =
        prefix(AcquisitionProperties.required(feed.controlPrefix(), "FULL_CONTROL_PREFIX"));
    String landing = prefix(feed.landingPrefix());

    check(!control.equals(landing), "Control and Landing prefixes must differ");
    check(packageId.equals(request.packageId()), "Unexpected FULL package");

    String versionPrefix = landing + "full/" + packageId + "/" + request.versionId() + "/";
    String bucket = acquisition.controlBucket();
    var store = new ReleaseStore(s3);
    JsonNode manifest = loadAndValidateManifest(store, bucket, versionPrefix, request);
    if (manifest == null) {
      return new ExtractionResult(IN_PROGRESS, "FULL", request.versionId(), null, null, List.of());
    }

    String stateKey = versionPrefix + "manifest/unpack_state.json";
    State state = loadOrInitaliseState(store, bucket, versionPrefix, stateKey, manifest);
    return extractFiles(request, context, store, versionPrefix, stateKey, state);
  }

  private ExtractionResult extractFiles(
      FullExtractionRequest request,
      Context context,
      ReleaseStore store,
      String versionPrefix,
      String stateKey,
      State state)
      throws IOException,
          NoSuchAlgorithmException,
          StreamingS3.ChecksumMismatchException,
          StreamingS3.ExistingObjectVerificationException {
    String bucket = acquisition.controlBucket();
    List<InputFile> files = getFilesFromState(state.value(), versionPrefix);
    var entries = state.value().path(FILES);

    if (state.value().path(STATE_COMPLETE).asBoolean()) {
      LOG.info("FULL version already unpacked", Map.of("version", request.versionId()));
      return result(COMPLETE, request.versionId(), entries, null);
    }

    for (InputFile file : files) {
      if (alreadyUnpacked(file, entries)) continue;
      if (context.getRemainingTimeInMillis() <= FILE_START_MARGIN_MILLIS) {
        return result(IN_PROGRESS, request.versionId(), entries, null);
      }

      String scan = store.scan(bucket, file.key(), file.versionId());
      if (PENDING.equals(scan)) {
        return result(IN_PROGRESS, request.versionId(), entries, null);
      }
      if (!ReleaseStore.CLEAN.equals(scan)) {
        return scanRejected(request, entries, file.key(), scan);
      }

      try {
        state = unpackAndMarkFile(file, context, store, bucket, versionPrefix, stateKey, state);
      } catch (ExtractionComponent.CsvHeaderException
          | ExtractionComponent.MissingPrimaryCsvException
          | ExtractionComponent.ArchiveValidationException
          | ZipException e) {
        return rejected(request, entries, file.key(), rejectionCode(e), e);
      }

      state = saveState(bucket, stateKey, state);
      entries = state.value().path(FILES);
    }
    state.value().put(STATE_COMPLETE, true);
    saveState(bucket, stateKey, state);
    return result(COMPLETE, request.versionId(), entries, null);
  }

  private static boolean alreadyUnpacked(InputFile file, JsonNode entries) {
    JsonNode entry = entries.path(file.key());
    String status = required(entry, STATUS);
    if (UNPACKED.equals(status)) {
      check(
          entry.path(OUTPUT_KEYS).isArray() && !entry.path(OUTPUT_KEYS).isEmpty(),
          "Unpacked file has no output keys");
      return true;
    }
    check(PENDING.equals(status), "Unknown unpack status");
    return false;
  }

  private static ExtractionResult scanRejected(
      FullExtractionRequest request, JsonNode entries, String key, String scan) {
    String code = "THREATS_FOUND".equals(scan) ? "THREATS_FOUND" : "SCAN_INCONCLUSIVE";
    LOG.error(
        "FULL file scan requires attention",
        Map.of("key", key, "scan_status", scan, "reason_code", code));
    return result(
        NEEDS_ATTENTION, request.versionId(), entries, new ExtractionResult.Failure(key, code));
  }

  private State unpackAndMarkFile(
      InputFile file,
      Context context,
      ReleaseStore store,
      String bucket,
      String versionPrefix,
      String stateKey,
      State state)
      throws IOException,
          NoSuchAlgorithmException,
          StreamingS3.ChecksumMismatchException,
          StreamingS3.ExistingObjectVerificationException {
    String key = file.key();
    String versionId = file.versionId();
    check(versionId.equals(store.version(bucket, key)), "FULL file changed after scan");
    var outputs = new ArrayList<String>();
    var skipped = new ArrayList<String>();
    var checkpoint = new MemberState(state, bucket, stateKey, key);

    String outputPrefix = versionPrefix + DOWNLOADS_PREFIX + file.featureType() + "/";
    if ("ZIP".equals(file.type())) {
      zipExtractor.extractFullZip(
          new ExtractionComponent.ZipTask(key, versionId, file.featureType(), outputPrefix, bucket),
          context,
          outputs,
          skipped,
          checkpoint);
    } else {
      try (var input =
          s3.getObject(
              GetObjectRequest.builder().bucket(bucket).key(key).versionId(versionId).build())) {
        String output = outputPrefix + file.featureType() + ORDER_SUMMARY_SUFFIX;
        new StreamingS3(s3, context::getRemainingTimeInMillis)
            .putIdempotent(input, bucket, output, MAX_SUMMARY_BYTES);
        outputs.add(output);
      }
    }

    state = checkpoint.state;
    var entry = (ObjectNode) state.value().path(FILES).path(key);
    entry.put(STATUS, UNPACKED);
    var outputKeys = entry.putArray(OUTPUT_KEYS);
    outputs.forEach(outputKeys::add);
    var skippedEntries = entry.putArray(SKIPPED);
    skipped.forEach(skippedEntries::add);
    return state;
  }

  private final class MemberState implements ExtractionComponent.MemberCheckpoint {
    private State state;
    private final String bucket;
    private final String stateKey;
    private final String zipKey;

    private MemberState(State state, String bucket, String stateKey, String zipKey) {
      this.state = state;
      this.bucket = bucket;
      this.stateKey = stateKey;
      this.zipKey = zipKey;
    }

    private JsonNode members() {
      return state.value().path(FILES).path(zipKey).path(MEMBERS);
    }

    @Override
    public Set<String> names() {
      return members().isMissingNode()
          ? Set.of()
          : members().properties().stream().map(Map.Entry::getKey).collect(Collectors.toSet());
    }

    @Override
    public ExtractionComponent.Member completed(String name) {
      JsonNode member = members().path(name);
      if (member.isMissingNode()) return null;
      check(
          member.isObject() && member.path(BYTES).isIntegralNumber(),
          "Invalid FULL member checkpoint: " + name);
      check(
          MEMBER_COMPLETE.equals(required(member, STATUS))
              || MEMBER_RECOVERING.equals(required(member, STATUS)),
          "Invalid FULL member status: " + name);
      String action = required(member, ACTION);
      if (MEMBER_SKIP.equals(action)) {
        check(
            !member.has(OUTPUT_KEY) && !member.has(VERSION_ID) && !member.has(SHA256),
            "Invalid skipped FULL member checkpoint: " + name);
      }
      String outputKey = MEMBER_SKIP.equals(action) ? null : required(member, OUTPUT_KEY);
      String versionId = MEMBER_SKIP.equals(action) ? null : required(member, VERSION_ID);
      String sha256 = MEMBER_SKIP.equals(action) ? null : required(member, SHA256);
      return new ExtractionComponent.Member(
          action, member.path(BYTES).longValue(), outputKey, versionId, sha256);
    }

    @Override
    public boolean recovering(String name) {
      return MEMBER_RECOVERING.equals(members().path(name).path(STATUS).asText());
    }

    @Override
    public void markRecovering(String name, ExtractionComponent.Member member) throws IOException {
      check(
          member.outputKey() != null && !recovering(name), "Invalid FULL member recovery: " + name);
      ObjectNode zip = (ObjectNode) state.value().path(FILES).path(zipKey);
      ObjectNode checkpoints =
          zip.path(MEMBERS).isMissingNode()
              ? zip.putObject(MEMBERS)
              : (ObjectNode) zip.path(MEMBERS);
      ObjectNode recorded = checkpoints.putObject(name);
      recorded
          .put(STATUS, MEMBER_RECOVERING)
          .put(ACTION, member.action())
          .put(BYTES, member.bytes())
          .put(OUTPUT_KEY, member.outputKey())
          .put(VERSION_ID, member.versionId())
          .put(SHA256, member.sha256());
      state = saveState(bucket, stateKey, state);
    }

    @Override
    public void save(String name, ExtractionComponent.Member member) throws IOException {
      ObjectNode zip = (ObjectNode) state.value().path(FILES).path(zipKey);
      ObjectNode checkpoints;
      if (zip.path(MEMBERS).isMissingNode()) {
        checkpoints = zip.putObject(MEMBERS);
      } else {
        check(zip.path(MEMBERS).isObject(), "Invalid FULL member checkpoints");
        checkpoints = (ObjectNode) zip.path(MEMBERS);
      }
      check(
          !checkpoints.has(name) || recovering(name), "Duplicate FULL member checkpoint: " + name);
      var recorded =
          checkpoints
              .putObject(name)
              .put(STATUS, MEMBER_COMPLETE)
              .put(ACTION, member.action())
              .put(BYTES, member.bytes());
      if (member.outputKey() != null) {
        recorded
            .put(OUTPUT_KEY, member.outputKey())
            .put(VERSION_ID, member.versionId())
            .put(SHA256, member.sha256());
      }
      state = saveState(bucket, stateKey, state);
    }
  }

  private static String rejectionCode(IOException cause) {
    if (cause instanceof ExtractionComponent.CsvHeaderException) {
      return "CSV_HEADER_MISMATCH";
    }
    if (cause instanceof ExtractionComponent.MissingPrimaryCsvException) {
      return "MISSING_PRIMARY_CSV";
    }
    if (cause instanceof ExtractionComponent.ArchiveValidationException) {
      return "ARCHIVE_VALIDATION_FAILED";
    }
    if (cause instanceof ZipException) {
      return "INVALID_ZIP";
    }
    throw new IllegalArgumentException("Unexpected file rejection", cause);
  }

  private static ExtractionResult rejected(
      FullExtractionRequest request, JsonNode entries, String key, String code, IOException cause) {
    LOG.error("FULL file rejected", cause, Map.of("key", key, "reason_code", code));

    return result(
        NEEDS_ATTENTION, request.versionId(), entries, new ExtractionResult.Failure(key, code));
  }

  private static ExtractionResult result(
      ExtractionResult.Status status,
      String version,
      JsonNode entries,
      ExtractionResult.Failure failure) {
    int done = 0;
    for (var entry : entries.properties()) {
      if (UNPACKED.equals(entry.getValue().path(STATUS).asText())) done++;
    }

    return new ExtractionResult(
        status,
        "FULL",
        version,
        done,
        entries.size() - done,
        failure == null ? List.of() : List.of(failure));
  }

  private static JsonNode loadAndValidateManifest(
      ReleaseStore store, String bucket, String versionPrefix, FullExtractionRequest request)
      throws IOException {
    JsonNode manifest = store.read(bucket, versionPrefix + "manifest/data_package_manifest.json");
    if (manifest == null) return null;
    check(
        "FULL".equals(required(manifest, "mode"))
            && request.packageId().equals(required(manifest, "packageId"))
            && request.versionId().equals(required(manifest, VERSION_ID))
            && manifest.has("previous")
            && manifest.get("previous").isNull(),
        "FULL version manifest does not match request");

    return manifest;
  }

  private State loadOrInitaliseState(
      ReleaseStore store, String bucket, String versionPrefix, String stateKey, JsonNode manifest)
      throws IOException {
    String manifestSha = sha(manifest.toString().getBytes(StandardCharsets.UTF_8));
    State state = readState(bucket, stateKey);

    if (state == null) {
      List<InputFile> files = getDownloadFiles(store, bucket, versionPrefix);
      var initial =
          JSON.createObjectNode().put("manifestSha256", manifestSha).put(STATE_COMPLETE, false);
      var entries = initial.putObject(FILES);

      for (InputFile file : files) {
        entries
            .putObject(file.key())
            .put(VERSION_ID, file.versionId())
            .put(FEATURE_TYPE, file.featureType())
            .put("type", file.type())
            .put(STATUS, PENDING)
            .putArray(OUTPUT_KEYS);
      }

      s3.putObject(
          PutObjectRequest.builder()
              .bucket(bucket)
              .key(stateKey)
              .ifNoneMatch("*")
              .contentType("application/json")
              .build(),
          RequestBody.fromString(initial.toString()));
      state = requireState(bucket, stateKey);
    }

    check(
        manifestSha.equals(required(state.value(), "manifestSha256")),
        "FULL unpack state does not match version manifest");
    check(state.value().path(STATE_COMPLETE).isBoolean(), "Invalid FULL completion flag");

    return state;
  }

  private static List<InputFile> getFilesFromState(JsonNode state, String versionPrefix) {
    JsonNode entries = state.path(FILES);
    check(entries.isObject() && !entries.isEmpty(), "FULL unpack state has no files");

    var files = new ArrayList<InputFile>();
    for (var field : entries.properties()) {
      String key = field.getKey();
      JsonNode entry = field.getValue();
      String versionId = required(entry, VERSION_ID);
      String featureType = required(entry, FEATURE_TYPE);
      String type = required(entry, "type");

      String filename =
          switch (type) {
            case "ZIP" -> featureType + ".zip";
            case ORDER_SUMMARY -> featureType + ORDER_SUMMARY_SUFFIX;
            default -> throw new IllegalArgumentException("Unexpected FULL file type: " + type);
          };

      check(
          !versionId.isBlank() && key.equals(versionPrefix + DOWNLOADS_PREFIX + filename),
          "Invalid FULL file entry");
      extractionHeader(featureType);

      String status = required(entry, STATUS);
      check(PENDING.equals(status) || UNPACKED.equals(status), "Unknown unpacked status");
      check(entry.path(OUTPUT_KEYS).isArray(), "Invalid FULL output keys");
      check(!entry.has(SKIPPED) || entry.path(SKIPPED).isArray(), "Invalid FULL skipped entries");
      check(
          !entry.has(MEMBERS) || ("ZIP".equals(type) && entry.path(MEMBERS).isObject()),
          "Invalid FULL member checkpoints");

      if (UNPACKED.equals(status)) {
        check(!entry.path(OUTPUT_KEYS).isEmpty(), "Unpacked file has no output keys");
      }

      files.add(new InputFile(key, versionId, featureType, type));
    }

    if (state.path(STATE_COMPLETE).asBoolean()) {
      check(
          files.stream()
              .allMatch(file -> UNPACKED.equals(entries.path(file.key()).path(STATUS).asText())),
          "FULL unpack state is marked complete but has pending files");
    }

    return files;
  }

  private static String prefix(String configured) {
    check(configured != null, "Missing feed prefix");
    if (configured.isEmpty()) return "";
    check(
        !configured.startsWith("/")
            && !configured.contains("..")
            && !configured.contains("//")
            && configured.matches("[a-zA-Z0-9_/-]+"),
        "Invalid feed prefix");
    return configured.endsWith("/") ? configured : configured + "/";
  }

  private record InputFile(String key, String versionId, String featureType, String type) {}

  private static List<InputFile> getDownloadFiles(
      ReleaseStore store, String bucket, String versionPrefix) {
    var files = new ArrayList<InputFile>();
    String inputPrefix = versionPrefix + DOWNLOADS_PREFIX;

    for (String key : store.keys(bucket, inputPrefix)) {
      String name = key.substring(inputPrefix.length());
      if (name.matches("([a-z][a-z0-9_]*)/\\1(?:_[a-z0-9_]+)?\\.csv")
          || name.matches("([a-z][a-z0-9_]*)/\\1_orderSummary\\.json")) continue;

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

      extractionHeader(featureType);
      files.add(new InputFile(key, store.version(bucket, key), featureType, type));
    }

    check(!files.isEmpty(), "FULL version has no input files");
    return files;
  }

  private record State(ObjectNode value, String stateTag) {}

  private State readState(String bucket, String key) throws IOException {
    var objects =
        s3.listObjectsV2(
            ListObjectsV2Request.builder().bucket(bucket).prefix(key).maxKeys(1).build());
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

  private State saveState(String bucket, String key, State state) throws IOException {
    s3.putObject(
        PutObjectRequest.builder()
            .bucket(bucket)
            .key(key)
            .ifMatch(state.stateTag())
            .contentType("application/json")
            .build(),
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
