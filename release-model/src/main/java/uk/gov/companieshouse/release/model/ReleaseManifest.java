package uk.gov.companieshouse.release.model;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;

import java.time.LocalDate;
import java.util.Arrays;
import java.util.EnumMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

/** Validated immutable metadata; the original JSON text preserves release identity and digest. */
public record ReleaseManifest(UUID id, SupplyType type, LocalDate previous, LocalDate target,
                                    String dataPackageId, String versionId,
                                    Map<Dataset, FileMetadata> files, String document) {

  private static final String RELEASE_ID = "releaseId";
  private static final String MODE = "mode";
  private static final String PREVIOUS_DATE = "previous";
  private static final String TARGET_DATE = "target";
  private static final String DATA_PACKAGE_ID = "dataPackageId";
  private static final String VERSION_ID = "versionId";

  private static final Set<String> ALLOWED_FIELDS = new HashSet<>(List.of(RELEASE_ID, MODE, PREVIOUS_DATE, TARGET_DATE,
          DATA_PACKAGE_ID, VERSION_ID));

  static {
    Arrays.stream(Dataset.values()).forEach(ReleaseManifest::addDatasetName);
  }

  private static void addDatasetName (Dataset dataset) {
    ALLOWED_FIELDS.add(dataset.datasetName());
  }

  public record Zip(String md5, String fileName, String url) {}

  public record FileMetadata(OrderSummary summary, Zip zip) {}

  public FileMetadata file(Dataset dataset) {
    return files.get(dataset);
  }

  /** A detached wire-format copy for adapters that need to publish or amend a description. */
  public JsonNode json() throws com.fasterxml.jackson.core.JsonProcessingException {
    return ReleaseJson.MAPPER.readTree(document);
  }

  public static ReleaseManifest read(JsonNode node) throws Exception {
    if (!node.isObject()) throw new IllegalArgumentException("Manifest must be an object");

    verifyFields(node);

    UUID id = generateUUIDFromNode(node);

    SupplyType type = SupplyType.valueOf(text(node, MODE));

    LocalDate previous = parseDateFromNode(node, PREVIOUS_DATE);

    if (type == SupplyType.COU && previous == null)
      throw new IllegalArgumentException("COU requires predecessor");

    LocalDate target = parseDateFromNode(node, TARGET_DATE);

    validateDates(previous, target);

    String dataPackageId = text(node, DATA_PACKAGE_ID);

    String versionId = text(node, VERSION_ID);

    var files = new EnumMap<Dataset, FileMetadata>(Dataset.class);

    for (var dataset : Dataset.values()) {
      var file = node.path(dataset.datasetName());

      if (file != null && !file.isEmpty()) {

        JsonNode summary = extractSummaryFromFile(file);

        OrderSummary order = extractOrderSummary(summary, dataset, type, previous, target);

        files.put(dataset, new FileMetadata(order, extractZip(file)));
      }
    }
    return new ReleaseManifest(id, type, previous, target, dataPackageId, versionId, files, node.toString());
  }

  public OrderSummary summary(Dataset dataset) {
    return file(dataset).summary();
  }

  public static String text(JsonNode node, String field) {
    if (!node.path(field).isTextual() || node.path(field).asText().isBlank())
      throw new IllegalArgumentException("Missing " + field);
    return node.path(field).asText();
  }

  private static void validateDates(LocalDate previous, LocalDate target) {
    if (target != null && previous != null && !target.isAfter(previous))
      throw new IllegalArgumentException("Invalid release interval");
  }

  private static UUID generateUUIDFromNode(JsonNode node) {
    return UUID.fromString(text(node, RELEASE_ID));
  }

  private static LocalDate parseDateFromNode(JsonNode node, String field) {
    return node.hasNonNull(field) ? LocalDate.parse(text(node, field)) : null;
  }

  private static void verifyFields(JsonNode node) {
    node.fieldNames().forEachRemaining(ReleaseManifest::verifyField);
  }

  private static void verifyField(String field) {
    if (!ALLOWED_FIELDS.contains(field))
      throw new IllegalArgumentException("Unexpected manifest field: " + field);
  }

  private static JsonNode extractSummaryFromFile(JsonNode file) {
    JsonNode summary = file.path("summary");
    if (!summary.path("recordCount").isIntegralNumber()
            || !summary.path("recordCount").canConvertToLong())
      throw new IllegalArgumentException("Invalid recordCount");
    return summary;
  }

  private static OrderSummary extractOrderSummary(JsonNode summary, Dataset dataset, SupplyType type,
                                                  LocalDate previous, LocalDate target) {
    OrderSummary order;

    try {
      order = ReleaseJson.MAPPER.treeToValue(summary, OrderSummary.class);
      order.validate(DatasetCatalog.table(dataset), type, previous, target);
    } catch (JsonProcessingException jsonProcessingException) {
      throw new IllegalArgumentException("Invalid Order Node", jsonProcessingException);
    }
    return order;
  }

  private static Zip extractZip(JsonNode file) {
    if (!text(file.path("zip"), "md5").matches("(?i)[a-f0-9]{32}"))
      throw new IllegalArgumentException("Invalid ZIP MD5");
    JsonNode zip = file.path("zip");
    return new Zip(
            text(zip, "md5"),
            zip.path("fileName").asText(null),
            zip.path("url").asText(null));
  }
}
