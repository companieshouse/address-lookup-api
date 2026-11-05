package uk.gov.companieshouse.addresslookup.dbmigrate;

import software.amazon.awssdk.services.s3.S3Client;
import software.amazon.awssdk.services.ssm.SsmClient;
import uk.gov.companieshouse.logging.Logger;
import uk.gov.companieshouse.logging.LoggerFactory;

import java.io.IOException;
import java.io.InputStream;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.FileAttribute;
import java.nio.file.attribute.PosixFilePermission;
import java.nio.file.attribute.PosixFilePermissions;
import java.sql.SQLException;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Set;

import static uk.gov.companieshouse.addresslookup.dbmigrate.MigrationException.Code.BUNDLE_CHECKSUM_MISMATCH;
import static uk.gov.companieshouse.addresslookup.dbmigrate.MigrationException.Code.DATABASE_UNAVAILABLE;
import static uk.gov.companieshouse.addresslookup.dbmigrate.MigrationException.Code.REPAIR_DISABLED;

/** Fetch the pinned snapshot, resolve credentials, run one Flyway command, report what happened. */
public final class MigrationService {
  private static final Logger LOG = LoggerFactory.getLogger(MigrationService.class.getName());
  private static final int DB_CONNECTION_VALID_TIMEOUT_SECONDS = 5;
  private static final long NANOS_PER_MILLI = 1_000_000;

  private final MigrationConfig migrationConfig;
  private final BundleSource s3BundlesSource;
  private final DbCredentialsSource dbCredentialsSource;
  private final ConnectionFactory dbConnectionFactory;
  private final FlywayRunner flywayRunner;

  public MigrationService(
      MigrationConfig migrationConfig,
      BundleSource s3BundlesSource,
      DbCredentialsSource dbCredentialsSource,
      ConnectionFactory dbConnectionFactory,
      FlywayRunner flywayRunner) {
    this.migrationConfig = migrationConfig;
    this.s3BundlesSource = s3BundlesSource;
    this.dbCredentialsSource = dbCredentialsSource;
    this.dbConnectionFactory = dbConnectionFactory;
    this.flywayRunner = flywayRunner;
  }

  public static MigrationService fromEnvironment() {
    return new MigrationService(
        MigrationConfig.fromEnv(System.getenv()),
        new S3BundleSource(S3Client.create()),
        new ParameterStoreDbCredentialsSource(SsmClient.create()),
        ConnectionFactory.jdbc(),
        new FlywayRunner());
  }

  public Map<String, Object> handle(MigrationRequest request) {
    // Validation: everything that can fail before the database is touched.
    validateRepairAllowed(request);
    long started = System.nanoTime();
    Path tempWorkDir = createTempWorkDir();
    try {
      SnapshotZipExtractor.ExtractedSnapshot extractedSqlFiles = extractSnapshotZipFile(request, tempWorkDir);
      LOG.info(
          "Extracted snapshot",
          Map.of(
              "bytes", extractedSqlFiles.copiedZipFileBytes(),
              "sqlFileCount", extractedSqlFiles.sqlFileCount()));
      validateChecksum(request, extractedSqlFiles);
      LOG.info("Snapshot checksum verified", Map.of("sha256", extractedSqlFiles.zipFileSha256()));

      // Execution: connect and run the single Flyway command.
      FlywayRunner.Outcome flywayResult = executeFlywayCommand(request, extractedSqlFiles);
      return constructMigrationResult(request,
              extractedSqlFiles,
              flywayResult,
              (System.nanoTime() - started) / NANOS_PER_MILLI);
    } finally {
      deleteTempWorkDirQuietly(tempWorkDir);
      LOG.debug("Deleted temporary work directory", Map.of("path", tempWorkDir.toString()));
    }
  }

  private void validateRepairAllowed(MigrationRequest request) {
    if (request.command() == Command.REPAIR && !migrationConfig.allowRepair())
      throw new MigrationException(
          REPAIR_DISABLED, "Repair is disabled for this environment. Set ALLOW_REPAIR=true to enable it.");
  }

  /** Download the pinned object version and extract it safely into the work directory. */
  private SnapshotZipExtractor.ExtractedSnapshot extractSnapshotZipFile(MigrationRequest request, Path tempWorkDir) {
    try (InputStream inputStreamZipFile = s3BundlesSource.open(migrationConfig.snapshotBucket(), request.key(), request.versionId())) {
      return SnapshotZipExtractor.copyAndExtract(inputStreamZipFile, tempWorkDir, migrationConfig.maxBundleBytes(), migrationConfig.maxBundleEntries());
    } catch (IOException e) {
      throw new UncheckedIOException("Cannot read snapshot", e);
    }
  }

  /** The bytes must be the ones the pipeline gathered from the tagged commit. */
  private void validateChecksum(MigrationRequest request, SnapshotZipExtractor.ExtractedSnapshot extractedSqlFiles) {
    if (!extractedSqlFiles.zipFileSha256().equals(request.sha256()))
      throw new MigrationException(
          BUNDLE_CHECKSUM_MISMATCH,
          request.key() + " has SHA-256 " + extractedSqlFiles.zipFileSha256() + ", expected " + request.sha256());
  }

  // ---- Flyway execution ----
  private FlywayRunner.Outcome executeFlywayCommand(MigrationRequest request, SnapshotZipExtractor.ExtractedSnapshot extractedSqlFiles) {
    DatabaseCredentials dbCredentials = dbCredentialsSource.get(migrationConfig.dbCredentialsParameterPath());
    LOG.info("Connecting to the database");
    validateDbConnectionAvailable(dbCredentials);
    LOG.info(
        "Database connection is valid, running Flyway",
        Map.of("command", request.command().wireName(), "lockTimeout", migrationConfig.lockTimeout()));
    return flywayRunner.run(request.command(), extractedSqlFiles.migrations(), () -> dbConnectionFactory.open(dbCredentials), migrationConfig.lockTimeout());
  }

  /** Open and close a probe connection so that a connection failure is reported as DATABASE_UNAVAILABLE before Flyway starts. */
  private void validateDbConnectionAvailable(DatabaseCredentials dbCredentials) {
    // try-with-resources opens the connection here and closes it automatically at the end of the block.
    try (var dbConnection = dbConnectionFactory.open(dbCredentials)) {
      if (!dbConnection.isValid(DB_CONNECTION_VALID_TIMEOUT_SECONDS))
        throw new MigrationException(DATABASE_UNAVAILABLE, "Database connection is not valid");
    } catch (SQLException ignored) {
      // The connection opened successfully, so a failure while validating or closing it does not matter.
    }
  }

  private Map<String, Object> constructMigrationResult(
      MigrationRequest request, SnapshotZipExtractor.ExtractedSnapshot extractedSqlFiles, FlywayRunner.Outcome flywayResult, long millis) {
    var result = new LinkedHashMap<String, Object>();
    result.put("status", "SUCCESS");
    result.put("command", request.command().wireName());
    result.put(
        "snapshot",
        Map.of(
            "bucket", migrationConfig.snapshotBucket(),
            "key", request.key(),
            "versionId", request.versionId(),
            "sha256", extractedSqlFiles.zipFileSha256(),
            "bytes", extractedSqlFiles.copiedZipFileBytes(),
            "files", extractedSqlFiles.sqlFileCount()));
    result.put("schemaVersion", flywayResult.latestAppliedSchemaVersion());
    switch (request.command()) {
      case MIGRATE -> result.put("applied", flywayResult.appliedScriptFileNames());
      case REPAIR -> {
        result.put("repaired", flywayResult.repairedScriptFileNames());
        result.put("repairActions", flywayResult.repairActions());
      }
      case INFO -> result.put("pending", flywayResult.pendingScriptFileNames());
      case VALIDATE -> {
        // Always true here, because FlywayRunner throws VALIDATION_FAILED when validation fails.
        result.put("valid", true);
        result.put("pending", flywayResult.pendingScriptFileNames());
      }
    }
    result.put("durationMs", millis);
    return result;
  }

  private static Path createTempWorkDir() {
    // Only the owner may read, write or enter the directory.
    FileAttribute<Set<PosixFilePermission>> ownerOnlyPermissions =
        PosixFilePermissions.asFileAttribute(PosixFilePermissions.fromString("rwx------"));
    try {
      return Files.createTempDirectory("db-migrate-", ownerOnlyPermissions);
    } catch (IOException e) {
      throw new UncheckedIOException(e);
    }
  }

  /** Warm execution environments reuse /tmp, so always clean up. */
  private static void deleteTempWorkDirQuietly(Path tempWorkDir) {
    try (var paths = Files.walk(tempWorkDir)) {
      // Delete children before their parent directories.
      paths.sorted(Comparator.reverseOrder()).forEach(MigrationService::deletePathQuietly);
    } catch (IOException ignored) {
      // Best effort: the directory name is unique per invocation.
    }
  }

  /** Deletes one file or empty directory. A failure must not stop the remaining paths being deleted. */
  private static void deletePathQuietly(Path path) {
    try {
      Files.deleteIfExists(path);
    } catch (IOException ignored) {
      // Best effort: the directory name is unique per invocation.
    }
  }
}
