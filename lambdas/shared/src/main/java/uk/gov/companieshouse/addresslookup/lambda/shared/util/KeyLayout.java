package uk.gov.companieshouse.addresslookup.lambda.shared.util;

import java.time.LocalDate;
import java.time.format.DateTimeParseException;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import uk.gov.companieshouse.release.model.Dataset;

/** Shared S3 key layouts for acquisition objects and their legacy counterparts. */
public class KeyLayout {
  private static final String UUID =
      "[0-9a-fA-F]{8}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{12}";
  private static final Pattern OBJECT =
      Pattern.compile("^(zips|csv)/(\\w+-\\w+)/([a-z0-9_]+)\\.(zip|csv)$");
  private static final Pattern SCANNED_OBJECT =
      Pattern.compile("^Scanned/(" + UUID + ")/([a-z0-9_]+)\\.(zip|receipt\\.json)$");
  private static final Pattern MANIFEST =
      Pattern.compile("^acquisitions/(\\d{4}-\\d{2}-\\d{2})/(" + UUID + ")\\.json$");
  private static final Pattern COMPONENT = Pattern.compile("[A-Za-z0-9_-]+");
  private static final Pattern CSV_NAME = Pattern.compile("[A-Za-z0-9_-][A-Za-z0-9_.-]*\\.csv");
  private static final Pattern LANDING_OBJECT =
      Pattern.compile(
          "^(initial_full|daily_cou|monthly_full)/([A-Za-z0-9_-]+)/([A-Za-z0-9_-]+)/"
              + "(manifest/data_package_manifest\\.json|manifest/unpack_state\\.json|"
              + "downloads/([a-z0-9_]+)\\.zip|"
              + "downloads/([a-z0-9_]+)_orderSummary\\.json|"
              + "downloads/([a-z0-9_]+)/([A-Za-z0-9_-][A-Za-z0-9_.-]*\\.csv))$");

  private KeyLayout() {}

  public record ZipKey(String releaseId, Dataset dataset) {}

  public enum Feed {
    INITIAL_FULL("initial_full"),
    DAILY_COU("daily_cou"),
    MONTHLY_FULL("monthly_full");

    private final String prefix;

    Feed(String prefix) {
      this.prefix = prefix;
    }

    private static Feed fromPrefix(String prefix) {
      for (Feed feed : values()) {
        if (feed.prefix.equals(prefix)) return feed;
      }
      throw new IllegalArgumentException("Invalid feed");
    }
  }

  public record PackageVersion(Feed feed, String packageId, String versionId) {
    public PackageVersion {
      if (feed == null) throw new IllegalArgumentException("Missing feed");
      requireComponent(packageId, "package ID");
      requireComponent(versionId, "version ID");
    }
  }

  public enum LandingFileType {
    MANIFEST,
    UNPACK_STATE,
    ZIP,
    ORDER_SUMMARY,
    CSV
  }

  public record LandingObject(
      PackageVersion packageVersion, Dataset dataset, LandingFileType fileType, String fileName) {}

  public static String downloadCursorKey(String packageId) {
    return "cursors/" + requireComponent(packageId, "package ID") + ".json";
  }

  public static String extractionCursorKey(String packageId) {
    return "cursors/" + requireComponent(packageId, "package ID") + ".extraction.json";
  }

  public static String versionPrefix(PackageVersion version) {
    return version.feed().prefix + "/" + version.packageId() + "/" + version.versionId() + "/";
  }

  public static String dataPackageManifestKey(PackageVersion version) {
    return versionPrefix(version) + "manifest/data_package_manifest.json";
  }

  public static String unpackStateKey(PackageVersion version) {
    return versionPrefix(version) + "manifest/unpack_state.json";
  }

  public static String downloadZipKey(PackageVersion version, Dataset dataset) {
    return versionPrefix(version) + "downloads/" + dataset.datasetName() + ".zip";
  }

  public static String orderSummaryKey(PackageVersion version, Dataset dataset) {
    return versionPrefix(version) + "downloads/" + dataset.datasetName() + "_orderSummary.json";
  }

  public static String extractedCsvKey(PackageVersion version, Dataset dataset, String fileName) {
    requireCsvName(fileName);
    return versionPrefix(version) + "downloads/" + dataset.datasetName() + "/" + fileName;
  }

  public static LandingObject parseLandingObject(String key) {
    if (key == null) throw new IllegalArgumentException("Missing S3 key");
    Matcher match = LANDING_OBJECT.matcher(key);
    if (!match.matches()) throw new IllegalArgumentException("Invalid landing object key");
    var version =
        new PackageVersion(Feed.fromPrefix(match.group(1)), match.group(2), match.group(3));
    String suffix = match.group(4);
    if (suffix.equals("manifest/data_package_manifest.json")) {
      return new LandingObject(version, null, LandingFileType.MANIFEST, null);
    }
    if (suffix.equals("manifest/unpack_state.json")) {
      return new LandingObject(version, null, LandingFileType.UNPACK_STATE, null);
    }
    if (match.group(5) != null) {
      return new LandingObject(version, Dataset.named(match.group(5)), LandingFileType.ZIP, null);
    }
    if (match.group(6) != null) {
      return new LandingObject(
          version, Dataset.named(match.group(6)), LandingFileType.ORDER_SUMMARY, null);
    }
    requireCsvName(match.group(8));
    return new LandingObject(
        version, Dataset.named(match.group(7)), LandingFileType.CSV, match.group(8));
  }

  private static String requireComponent(String value, String name) {
    if (value == null || !COMPONENT.matcher(value).matches()) {
      throw new IllegalArgumentException("Invalid " + name);
    }
    return value;
  }

  private static void requireCsvName(String fileName) {
    if (fileName == null || !CSV_NAME.matcher(fileName).matches() || fileName.contains("..")) {
      throw new IllegalArgumentException("Invalid CSV filename");
    }
  }

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
          scanned.group(1),
          Dataset.named(scanned.group(2)),
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
