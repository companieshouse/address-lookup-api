package uk.gov.companieshouse.release.model;

import java.util.regex.Matcher;
import java.util.regex.Pattern;

/** Shared S3 key layout for scanned ZIPs, receipts and extracted CSVs. */
public final class KeyLayout {
  private static final Pattern OBJECT =
      Pattern.compile("^(zips|csv)/([A-Za-z0-9_]+-[A-Za-z0-9_]+)/([a-z0-9_]+)\\.(zip|csv)$");
  private static final Pattern SCANNED_OBJECT =
      Pattern.compile(
          "^Scanned/([0-9a-fA-F]{8}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{12})/([a-z0-9_]+)\\.(zip|receipt\\.json)$");

  private KeyLayout() {}

  public record ZipKey(String releaseId, Dataset dataset) {}

  public enum FileType {
    ZIP,
    CSV,
    RECEIPT
  }

  public record ObjectKey(String releaseId, Dataset dataset, FileType fileType) {}

  public static String zipKey(String releaseId, Dataset dataset) {
    String scannedKey = "Scanned/" + releaseId + "/" + dataset.datasetName() + ".zip";
    if (SCANNED_OBJECT.matcher(scannedKey).matches()) return scannedKey;
    return "zips/" + releaseId + "/" + dataset.datasetName() + ".zip";
  }

  public static String csvKey(String releaseId, Dataset dataset) {
    return "csv/" + releaseId + "/" + dataset.datasetName() + ".csv";
  }

  /**
   * @throws IllegalArgumentException if the key is not an exact supported ZIP, CSV or receipt key
   */
  public static ObjectKey parseObjectKey(String key) {
    if (key == null) throw new IllegalArgumentException("Missing S3 key");
    Matcher scanned = SCANNED_OBJECT.matcher(key);
    if (scanned.matches()) {
      return new ObjectKey(
          scanned.group(1), Dataset.named(scanned.group(2)),
          "zip".equals(scanned.group(3)) ? FileType.ZIP : FileType.RECEIPT);
    }
    Matcher m = OBJECT.matcher(key);
    if (!m.matches()) throw new IllegalArgumentException("Invalid object key");
    boolean zipKey = "zip".equals(m.group(4)) && "zips".equals(m.group(1));
    boolean csvKey = "csv".equals(m.group(4)) && "csv".equals(m.group(1));
    if (!zipKey && !csvKey) {
      throw new IllegalArgumentException("Invalid object key");
    }
    return new ObjectKey(
        m.group(2),
        Dataset.named(m.group(3)),
        "zip".equals(m.group(4)) ? FileType.ZIP : FileType.CSV);
  }

  /** @throws IllegalArgumentException if the key is not a valid ZIP key for a supported dataset */
  public static ZipKey parseZipKey(String key) {
    ObjectKey object = parseObjectKey(key);
    if (object.fileType() != FileType.ZIP) throw new IllegalArgumentException("Expected ZIP key");
    return new ZipKey(object.releaseId(), object.dataset());
  }

  /** @throws IllegalArgumentException if the key is not a valid CSV key for a supported dataset */
  public static ObjectKey parseCsvKey(String key) {
    ObjectKey object = parseObjectKey(key);
    if (object.fileType() != FileType.CSV) throw new IllegalArgumentException("Expected CSV key");
    return object;
  }
}
