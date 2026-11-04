package uk.gov.companieshouse.addresslookup.dbmigrate;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import java.util.HashMap;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class MigrationConfigTest {

  private static final String SNAPSHOT_BUCKET = "snapshot-bucket";
  private static final String DB_CREDENTIALS_PARAMETER_PATH = "/address-lookup/test/db/schema-owner";

  private static Map<String, String> buildEnvironmentVariables(String... pairs) {
    var environmentVariables = new HashMap<String, String>();
    environmentVariables.put("SNAPSHOT_BUCKET", SNAPSHOT_BUCKET);
    environmentVariables.put("DB_CREDENTIALS_PARAMETER", DB_CREDENTIALS_PARAMETER_PATH);
    for (int i = 0; i < pairs.length; i += 2) {
      if (pairs[i + 1] == null) environmentVariables.remove(pairs[i]);
      else environmentVariables.put(pairs[i], pairs[i + 1]);
    }
    return environmentVariables;
  }

  @Test
  void fromEnvUsesDefaultsWhenOptionalVariablesAreMissing() {
    var migrationConfig = MigrationConfig.fromEnv(buildEnvironmentVariables());

    assertEquals(SNAPSHOT_BUCKET, migrationConfig.snapshotBucket());
    assertEquals(DB_CREDENTIALS_PARAMETER_PATH, migrationConfig.dbCredentialsParameterPath());
    assertFalse(migrationConfig.allowRepair());
    assertEquals("10s", migrationConfig.lockTimeout());
    assertEquals(50L * 1024 * 1024, migrationConfig.maxBundleBytes());
    assertEquals(1000, migrationConfig.maxBundleEntries());
  }

  @Test
  void fromEnvReadsEveryOverride() {
    var migrationConfig =
        MigrationConfig.fromEnv(
            buildEnvironmentVariables(
                "ALLOW_REPAIR", "true",
                "LOCK_TIMEOUT", "500ms",
                "MAX_BUNDLE_BYTES", "2048",
                "MAX_BUNDLE_ENTRIES", "7"));

    assertTrue(migrationConfig.allowRepair());
    assertEquals("500ms", migrationConfig.lockTimeout());
    assertEquals(2048, migrationConfig.maxBundleBytes());
    assertEquals(7, migrationConfig.maxBundleEntries());
  }

  @ParameterizedTest
  @ValueSource(strings = {"false", "yes", "1", "", "TRUE "})
  void onlyTheWordTrueEnablesRepair(String allowRepairValue) {
    var migrationConfig = MigrationConfig.fromEnv(buildEnvironmentVariables("ALLOW_REPAIR", allowRepairValue));

    assertFalse(migrationConfig.allowRepair(), allowRepairValue);
  }

  @Test
  void fromEnvAcceptsTrueInAnyCase() {
    assertTrue(MigrationConfig.fromEnv(buildEnvironmentVariables("ALLOW_REPAIR", "TRUE")).allowRepair());
  }

  @Test
  void missingSnapshotBucketIsRejected() {
    var environmentWithoutBucket = buildEnvironmentVariables("SNAPSHOT_BUCKET", null);
    var migrationException =
        assertThrows(IllegalStateException.class, () -> MigrationConfig.fromEnv(environmentWithoutBucket));

    assertEquals("Missing SNAPSHOT_BUCKET", migrationException.getMessage());
  }

  @ParameterizedTest
  @ValueSource(strings = {"", " "})
  void blankSnapshotBucketIsRejected(String blankSnapshotBucket) {
    var migrationException =
        assertThrows(
            IllegalStateException.class,
            () -> new MigrationConfig(blankSnapshotBucket, DB_CREDENTIALS_PARAMETER_PATH, false, "10s", 1, 1));

    assertEquals("Missing SNAPSHOT_BUCKET", migrationException.getMessage());
  }

  @Test
  void missingDbCredentialsParameterIsRejected() {
    var environmentWithoutParameter = buildEnvironmentVariables("DB_CREDENTIALS_PARAMETER", null);
    var migrationException =
        assertThrows(IllegalStateException.class, () -> MigrationConfig.fromEnv(environmentWithoutParameter));

    assertEquals("Missing DB_CREDENTIALS_PARAMETER", migrationException.getMessage());
  }

  @ParameterizedTest
  @ValueSource(strings = {"", " "})
  void blankDbCredentialsParameterPathIsRejected(String blankDbCredentialsParameterPath) {
    var migrationException =
        assertThrows(
            IllegalStateException.class,
            () -> new MigrationConfig(SNAPSHOT_BUCKET, blankDbCredentialsParameterPath, false, "10s", 1, 1));

    assertEquals("Missing DB_CREDENTIALS_PARAMETER", migrationException.getMessage());
  }

  @ParameterizedTest
  @ValueSource(strings = {"10", "10m", "0s", "10s'; DROP TABLE x; --", "", "1000000s", "-5s"})
  void lockTimeoutMustBeAPlainDuration(String invalidLockTimeout) {
    var migrationException =
        assertThrows(
            IllegalStateException.class,
            () -> new MigrationConfig(SNAPSHOT_BUCKET, DB_CREDENTIALS_PARAMETER_PATH, false, invalidLockTimeout, 1, 1),
            invalidLockTimeout);

    assertEquals("LOCK_TIMEOUT must look like 10s or 500ms", migrationException.getMessage());
  }

  @Test
  void nullLockTimeoutIsRejected() {
    assertThrows(
        IllegalStateException.class,
        () -> new MigrationConfig(SNAPSHOT_BUCKET, DB_CREDENTIALS_PARAMETER_PATH, false, null, 1, 1));
  }

  @ParameterizedTest
  @ValueSource(strings = {"1s", "10s", "500ms", "999999s"})
  void lockTimeoutAcceptsSecondsAndMilliseconds(String validLockTimeout) {
    var migrationConfig =
        new MigrationConfig(SNAPSHOT_BUCKET, DB_CREDENTIALS_PARAMETER_PATH, false, validLockTimeout, 1, 1);

    assertEquals(validLockTimeout, migrationConfig.lockTimeout());
  }

  @Test
  void maxBundleBytesMustBePositive() {
    for (long invalidMaxBundleBytes : new long[] {0, -1}) {
      var migrationException =
          assertThrows(
              IllegalStateException.class,
              () ->
                  new MigrationConfig(
                      SNAPSHOT_BUCKET, DB_CREDENTIALS_PARAMETER_PATH, false, "10s", invalidMaxBundleBytes, 1),
              String.valueOf(invalidMaxBundleBytes));

      assertEquals("Limits must be positive", migrationException.getMessage());
    }
  }

  @Test
  void maxBundleEntriesMustBePositive() {
    for (int invalidMaxBundleEntries : new int[] {0, -1}) {
      var migrationException =
          assertThrows(
              IllegalStateException.class,
              () ->
                  new MigrationConfig(
                      SNAPSHOT_BUCKET, DB_CREDENTIALS_PARAMETER_PATH, false, "10s", 1, invalidMaxBundleEntries),
              String.valueOf(invalidMaxBundleEntries));

      assertEquals("Limits must be positive", migrationException.getMessage());
    }
  }

  @Test
  void nonNumericLimitsFailWhileReadingTheEnvironment() {
    var environmentWithTextBytes = buildEnvironmentVariables("MAX_BUNDLE_BYTES", "abc");
    var maxBundleBytesException =
        assertThrows(IllegalStateException.class, () -> MigrationConfig.fromEnv(environmentWithTextBytes));
    assertEquals("MAX_BUNDLE_BYTES must be a whole number but was abc", maxBundleBytesException.getMessage());

    var environmentWithDecimalEntries = buildEnvironmentVariables("MAX_BUNDLE_ENTRIES", "1.5");
    var maxBundleEntriesException =
        assertThrows(IllegalStateException.class, () -> MigrationConfig.fromEnv(environmentWithDecimalEntries));
    assertEquals("MAX_BUNDLE_ENTRIES must be a whole number but was 1.5", maxBundleEntriesException.getMessage());
  }
}

