package uk.gov.companieshouse.release.model;

import com.fasterxml.jackson.databind.JsonNode;

import java.time.LocalDate;
import java.util.*;

/** Validated immutable metadata; the original JSON text preserves release identity and digest. */
public final class ReleaseManifest {
  public record Zip(String md5, String fileName, String url) {}

  public record FileMetadata(String packageId, String version, OrderSummary summary, Zip zip) {}

  private final UUID id;
  private final SupplyType type;
  private final LocalDate previous;
  private final LocalDate target;
  private final Map<Dataset, FileMetadata> files;
  private final String document;

  private ReleaseManifest(
      UUID id,
      SupplyType type,
      LocalDate previous,
      LocalDate target,
      Map<Dataset, FileMetadata> files,
      String document) {
    this.id = id;
    this.type = type;
    this.previous = previous;
    this.target = target;
    this.files = Map.copyOf(files);
    this.document = document;
  }

  @Override
  public boolean equals(Object other) {
    return other instanceof ReleaseManifest manifest && document.equals(manifest.document);
  }

  @Override
  public int hashCode() {
    return document.hashCode();
  }

  public UUID id() {
    return id;
  }

  public SupplyType type() {
    return type;
  }

  public LocalDate previous() {
    return previous;
  }

  public LocalDate target() {
    return target;
  }

  public Map<Dataset, FileMetadata> files() {
    return files;
  }

  public FileMetadata file(Dataset dataset) {
    return files.get(dataset);
  }

  public String document() {
    return document;
  }

  /** A detached wire-format copy for adapters that need to publish or amend a description. */
  public JsonNode json() throws com.fasterxml.jackson.core.JsonProcessingException {
    return ReleaseJson.MAPPER.readTree(document);
  }

  public static ReleaseManifest read(JsonNode node) throws Exception {
    if (!node.isObject()) throw new IllegalArgumentException("Manifest must be an object");
    var allowed = new HashSet<>(List.of("releaseId", "mode", "previous", "target"));
    for (var dataset : Dataset.values()) allowed.add(dataset.datasetName());
    node.fieldNames()
        .forEachRemaining(
            field -> {
              if (!allowed.contains(field))
                throw new IllegalArgumentException("Unexpected manifest field: " + field);
            });
    UUID id = UUID.fromString(text(node, "releaseId"));
    SupplyType type = SupplyType.valueOf(text(node, "mode"));
    LocalDate target = LocalDate.parse(text(node, "target"));
    LocalDate previous =
        node.hasNonNull("previous") ? LocalDate.parse(text(node, "previous")) : null;
    if (previous != null && !target.isAfter(previous))
      throw new IllegalArgumentException("Invalid release interval");
    if (type == SupplyType.COU && previous == null)
      throw new IllegalArgumentException("COU requires predecessor");
    var files = new EnumMap<Dataset, FileMetadata>(Dataset.class);
    for (var dataset : Dataset.values()) {
      var file = node.path(dataset.datasetName());
      var summary = file.path("summary");
      if (!summary.path("recordCount").isIntegralNumber()
          || !summary.path("recordCount").canConvertToLong())
        throw new IllegalArgumentException("Invalid recordCount");
      var order = ReleaseJson.MAPPER.treeToValue(summary, OrderSummary.class);
      order.validate(dataset.table(), type, previous, target);
      text(file, "package");
      text(file, "version");
      if (!text(file.path("zip"), "md5").matches("(?i)[a-f0-9]{32}"))
        throw new IllegalArgumentException("Invalid ZIP MD5");
      var zip = file.path("zip");
      files.put(
          dataset,
          new FileMetadata(
              text(file, "package"),
              text(file, "version"),
              order,
              new Zip(
                  text(zip, "md5"),
                  zip.path("fileName").asText(null),
                  zip.path("url").asText(null))));
    }
    return new ReleaseManifest(id, type, previous, target, files, node.toString());
  }

  public OrderSummary summary(Dataset dataset) {
    return file(dataset).summary();
  }

  public static String text(JsonNode node, String field) {
    if (!node.path(field).isTextual() || node.path(field).asText().isBlank())
      throw new IllegalArgumentException("Missing " + field);
    return node.path(field).asText();
  }
}
