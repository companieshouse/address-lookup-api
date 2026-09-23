package uk.gov.companieshouse.release.model;

import com.fasterxml.jackson.databind.JsonNode;

/** Exact immutable S3 object certified by scan evidence. */
public record ObjectReference(String key, String version, String sha256) {
  public ObjectReference {
    if (key == null
        || key.isBlank()
        || version == null
        || version.isBlank()
        || version.equals("null")) throw new IllegalArgumentException("Versioned object required");
    if (sha256 == null || !sha256.matches("[a-f0-9]{64}"))
      throw new IllegalArgumentException("Checksum required");
  }

  public static ObjectReference read(JsonNode node) {
    return new ObjectReference(
        ReleaseManifest.text(node, "key"),
        ReleaseManifest.text(node, "version"),
        ReleaseManifest.text(node, "sha256"));
  }
}
