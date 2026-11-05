package uk.gov.companieshouse.addresslookup.dbmigrate;

import java.util.Map;
import java.util.function.Function;
import java.util.regex.Pattern;

/** Environment configuration, read once per execution environment. */
public record MigrationConfig(
    String snapshotBucket,
    String dbCredentialsParameterPath,
    boolean allowRepair,
    String lockTimeout,
    long maxBundleBytes,
    int maxBundleEntries) {

  private static final Pattern DURATION = Pattern.compile("[1-9]\\d{0,5}(ms|s)");
  private static final String DEFAULT_LOCK_TIMEOUT = "10s";
  private static final long DEFAULT_MAX_BUNDLE_BYTES = 50L * 1024 * 1024; // 50 MiB
  private static final int DEFAULT_MAX_BUNDLE_ENTRIES = 1000;

  public MigrationConfig {
    if (snapshotBucket == null || snapshotBucket.isBlank())
      throw new IllegalStateException("Missing SNAPSHOT_BUCKET");
    if (dbCredentialsParameterPath == null || dbCredentialsParameterPath.isBlank())
      throw new IllegalStateException("Missing DB_CREDENTIALS_PARAMETER");
    // Interpolated into SET lock_timeout, so only a plain duration is allowed.
    if (lockTimeout == null || !DURATION.matcher(lockTimeout).matches())
      throw new IllegalStateException("LOCK_TIMEOUT must look like 10s or 500ms");
    if (maxBundleBytes <= 0 || maxBundleEntries <= 0)
      throw new IllegalStateException("Limits must be positive");
  }

  public static MigrationConfig fromEnv(Map<String, String> env) {
    return new MigrationConfig(
        env.get("SNAPSHOT_BUCKET"),
        env.get("DB_CREDENTIALS_PARAMETER"),
        Boolean.parseBoolean(env.getOrDefault("ALLOW_REPAIR", "false")),
        // A migration waiting this long for a lock fails rather than queueing API reads behind it.
        env.getOrDefault("LOCK_TIMEOUT", DEFAULT_LOCK_TIMEOUT),
        parseWholeNumber(
            "MAX_BUNDLE_BYTES", env.getOrDefault("MAX_BUNDLE_BYTES", String.valueOf(DEFAULT_MAX_BUNDLE_BYTES)), Long::parseLong),
        parseWholeNumber(
            "MAX_BUNDLE_ENTRIES",
            env.getOrDefault("MAX_BUNDLE_ENTRIES", String.valueOf(DEFAULT_MAX_BUNDLE_ENTRIES)),
            Integer::parseInt));
  }

  /** Parses an environment value such as {@code 1000}, and reports the variable name when it is not a whole number. */
  private static <T extends Number> T parseWholeNumber(String envName, String envValue, Function<String, T> parser) {
    try {
      return parser.apply(envValue);
    } catch (NumberFormatException e) {
      throw new IllegalStateException(envName + " must be a whole number but was " + envValue, e);
    }
  }
}
