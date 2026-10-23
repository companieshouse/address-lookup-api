package uk.gov.companieshouse.addresslookup.lambda.shared.util;

import lombok.experimental.UtilityClass;
import uk.gov.companieshouse.release.model.Dataset;

import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.time.LocalDate;
import java.time.format.DateTimeParseException;

/** Shared S3 key layout for scanned ZIPs, receipts, manifests and extracted CSVs. */
@UtilityClass
public class KeyLayout {
  private static final String UUID =
      "[0-9a-fA-F]{8}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{12}";
  private static final Pattern OBJECT =
      Pattern.compile("^(zips|csv)/(\\w+-\\w+)/([a-z0-9_]+)\\.(zip|csv)$");
  private static final Pattern SCANNED_OBJECT =
      Pattern.compile(
          "^Scanned/(" + UUID + ")/([a-z0-9_]+)\\.(zip|receipt\\.json)$");
  private static final Pattern MANIFEST =
      Pattern.compile("^acquisitions/(\\d{4}-\\d{2}-\\d{2})/(" + UUID + ")\\.json$");

  public record ZipKey(String releaseId, Dataset dataset) {}

  public enum FileType {
    ZIP,
    CSV,
    RECEIPT,
    MANIFEST
  }

  /** Dataset is absent for release-level manifests. */
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
   * @throws IllegalArgumentException if the key is not an exact supported object key
   */
  public static ObjectKey parseObjectKey(String key) {
    if (key == null) throw new IllegalArgumentException("Missing S3 key");
    Matcher manifest = MANIFEST.matcher(key);
    if (manifest.matches()) {
      try {
        LocalDate.parse(manifest.group(1));
      } catch (DateTimeParseException e) {
        throw new IllegalArgumentException("Invalid manifest date", e);
      }
      return new ObjectKey(manifest.group(2), null, FileType.MANIFEST);
    }
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
