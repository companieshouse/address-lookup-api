package uk.gov.companieshouse.release.model;

import java.util.regex.Matcher;
import java.util.regex.Pattern;

/** Shared S3 key layout. ZIPs are stored as {@code zips/<releaseId>/<dataset>.zip}. */
public final class KeyLayout {
  private static final Pattern ZIP =
      Pattern.compile("^zips/([A-Za-z0-9][A-Za-z0-9_-]*)/([a-z0-9_]+)\\.zip$");

  private KeyLayout() {}

  public record ZipKey(String releaseId, Dataset dataset) {}

  public static String zipKey(String releaseId, Dataset dataset) {
    return "zips/" + releaseId + "/" + dataset.datasetName() + ".zip";
  }

  /** @throws IllegalArgumentException if the key is not a valid ZIP key for a supported dataset */
  public static ZipKey parseZipKey(String key) {
    if (key == null) throw new IllegalArgumentException("Missing S3 key");
    Matcher m = ZIP.matcher(key);
    if (!m.matches()) throw new IllegalArgumentException("Invalid ZIP key");
    return new ZipKey(m.group(1), Dataset.named(m.group(2)));
  }
}
