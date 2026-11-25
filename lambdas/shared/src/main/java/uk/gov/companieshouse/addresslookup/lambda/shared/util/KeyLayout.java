package uk.gov.companieshouse.addresslookup.lambda.shared.util;

import uk.gov.companieshouse.release.model.Dataset;

import java.util.regex.Matcher;
import java.util.regex.Pattern;

/** Shared builders and parsers for Control and Landing S3 keys. */
public class KeyLayout {
    private static final String PACKAGE_ID = "package ID";
    private static final String JSON_EXTENSION = ".json";
    private static final String DOWNLOADS_PREFIX = "downloads/";
    private static final String LANDING_PREFIX = "landing/";
    private static final String CONTROL_PREFIX = "control/";
    private static final Pattern COMPONENT = Pattern.compile("[A-Za-z0-9_-]+");
    private static final Pattern CSV_NAME = Pattern.compile("[A-Za-z0-9_-][A-Za-z0-9_.-]*\\.csv");
    private static final Pattern LANDING_SUFFIX =
            Pattern.compile(
                    "manifest/data_package_manifest\\.json|manifest/unpack_state\\.json|"
                            + "downloads/([a-z0-9_]+)\\.zip|"
                            + "downloads/([a-z0-9_]+)_orderSummary\\.json|"
                            + "downloads/([a-z0-9_]+)/([^/]+)");

    private KeyLayout() {}

    public enum Feed {
        INITIAL_FULL("initial_full"),
        DAILY_COU("daily_cou"),
        MONTHLY_FULL("monthly_full");

        private final String prefix;

        Feed(String prefix) {
            this.prefix = prefix;
        }

        public static Feed fromPrefix(String prefix) {
            for (Feed feed : values()) {
                if (feed.prefix.equals(prefix)) return feed;
            }
            throw new IllegalArgumentException("Invalid feed");
        }
    }

    public record PackageVersion(Feed feed, String packageId, String versionId) {
        public PackageVersion {
            if (feed == null) throw new IllegalArgumentException("Missing feed");
            requireComponent(packageId, PACKAGE_ID);
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
            PackageVersion packageVersion,
            Dataset dataset,
            LandingFileType fileType,
            String fileName) {
        public boolean isMainCsv() {
            return fileType == LandingFileType.CSV
                    && dataset != null
                    && (dataset.datasetName() + ".csv").equals(fileName);
        }
    }

    public static String downloadCursorKey(String packageId) {
        return CONTROL_PREFIX + "cursors/" + requireComponent(packageId, PACKAGE_ID) + JSON_EXTENSION;
    }

    public static String extractionCursorKey(String packageId) {
        return CONTROL_PREFIX
                + "cursors/"
                + requireComponent(packageId, PACKAGE_ID)
                + ".extraction.json";
    }

    public static String reconciliationCursorKey(String name) {
        return CONTROL_PREFIX
                + "reconciliation/"
                + requireComponent(name, "reconciliation name")
                + JSON_EXTENSION;
    }

    public static String checksumKey(String key) {
        return CONTROL_PREFIX + "metadata/checksums/"
                + uk.gov.companieshouse.addresslookup.lambda.shared.runtime.RuntimeSupport.sha(
                        key.getBytes(java.nio.charset.StandardCharsets.UTF_8))
                + JSON_EXTENSION;
    }

    public static String versionPrefix(PackageVersion version) {
        return LANDING_PREFIX
                + version.feed().prefix + "/" + version.packageId() + "/" + version.versionId() + "/";
    }

    public static String dataPackageManifestKey(PackageVersion version) {
        return versionPrefix(version) + "manifest/data_package_manifest.json";
    }

    public static String unpackStateKey(PackageVersion version) {
        return CONTROL_PREFIX
                + version.feed().prefix
                + "/"
                + version.packageId()
                + "/"
                + version.versionId()
                + "/manifest/unpack_state.json";
    }

    public static String downloadZipKey(PackageVersion version, Dataset dataset) {
        return versionPrefix(version) + DOWNLOADS_PREFIX + dataset.datasetName() + ".zip";
    }

    public static String orderSummaryKey(PackageVersion version, Dataset dataset) {
        return versionPrefix(version)
                + DOWNLOADS_PREFIX
                + dataset.datasetName()
                + "_orderSummary.json";
    }

    public static String extractedCsvKey(PackageVersion version, Dataset dataset, String fileName) {
        requireCsvName(fileName);
        return versionPrefix(version) + DOWNLOADS_PREFIX + dataset.datasetName() + "/" + fileName;
    }

    public static LandingObject parseLandingObject(String key) {
        if (key == null) throw new IllegalArgumentException("Missing S3 key");
        if (!key.startsWith(LANDING_PREFIX))
            throw new IllegalArgumentException("Invalid landing object key");
        key = key.substring(LANDING_PREFIX.length());
        String[] parts = key.split("/", 4);
        if (parts.length != 4) throw new IllegalArgumentException("Invalid landing object key");
        Matcher match = LANDING_SUFFIX.matcher(parts[3]);
        if (!match.matches()) throw new IllegalArgumentException("Invalid landing object key");
        var version = new PackageVersion(Feed.fromPrefix(parts[0]), parts[1], parts[2]);
        String suffix = parts[3];
        if (suffix.equals("manifest/data_package_manifest.json")) {
            return new LandingObject(version, null, LandingFileType.MANIFEST, null);
        }

        if (suffix.equals("manifest/unpack_state.json")) {
            return new LandingObject(version, null, LandingFileType.UNPACK_STATE, null);
        }
        if (match.group(1) != null) {
            return new LandingObject(
                    version, Dataset.named(match.group(1)), LandingFileType.ZIP, null);
        }
        if (match.group(2) != null) {
            return new LandingObject(
                    version, Dataset.named(match.group(2)), LandingFileType.ORDER_SUMMARY, null);
        }
        requireCsvName(match.group(4));
        return new LandingObject(
                version, Dataset.named(match.group(3)), LandingFileType.CSV, match.group(4));
    }

    public static PackageVersion parsePackageVersionManifestKey(String key) {
        LandingObject object = parseLandingObject(key);
        if (object.fileType() != LandingFileType.MANIFEST) {
            throw new IllegalArgumentException("Expected data package manifest key");
        }
        return object.packageVersion();
    }

    public static boolean isDataPackageManifestKey(String key) {
        try {
            return parseLandingObject(key).fileType() == LandingFileType.MANIFEST;
        } catch (IllegalArgumentException invalidKey) {
            return false;
        }
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
        MANIFEST,
        UNPACK_STATE,
        ORDER_SUMMARY
    }

    /** Dataset is absent for release-level manifests. */
    public record ObjectKey(
            String releaseId, Dataset dataset, FileType fileType, PackageVersion packageVersion) {}

    public static String feedManifestPrefix(Feed feed) {
        return LANDING_PREFIX + feed.prefix + "/";
    }

    /**
     * @throws IllegalArgumentException if the key is not an exact Landing key
     */
    public static ObjectKey parseObjectKey(String key) {
        LandingObject landing = parseLandingObject(key);
        String identity =
                landing.packageVersion().packageId() + "-" + landing.packageVersion().versionId();
        FileType type =
                switch (landing.fileType()) {
                    case ZIP -> FileType.ZIP;
                    case CSV -> FileType.CSV;
                    case MANIFEST -> FileType.MANIFEST;
                    case UNPACK_STATE -> FileType.UNPACK_STATE;
                    case ORDER_SUMMARY -> FileType.ORDER_SUMMARY;
                };
        return new ObjectKey(identity, landing.dataset(), type, landing.packageVersion());
    }
}
