package uk.gov.companieshouse.addresslookup.dbmigrate;

import java.util.Map;
import java.util.regex.Pattern;

/** Environment configuration; read once per execution environment. */
public record MigrationConfig(
    String snapshotBucket,
    String credentialsParameter,
    boolean allowRepair,
    String lockTimeout,
    long maxBundleBytes,
    int maxBundleEntries) {

  private static final Pattern DURATION = Pattern.compile("[1-9][0-9]{0,5}(ms|s)");

  public MigrationConfig {
    if (snapshotBucket == null || snapshotBucket.isBlank())
      throw new IllegalStateException("Missing SNAPSHOT_BUCKET");
    if (credentialsParameter == null || credentialsParameter.isBlank())
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
        env.getOrDefault("LOCK_TIMEOUT", "10s"),
        Long.parseLong(env.getOrDefault("MAX_BUNDLE_BYTES", "52428800")),
        Integer.parseInt(env.getOrDefault("MAX_BUNDLE_ENTRIES", "1000")));
  }
}
