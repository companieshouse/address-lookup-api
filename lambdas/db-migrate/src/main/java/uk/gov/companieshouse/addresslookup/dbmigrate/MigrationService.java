package uk.gov.companieshouse.addresslookup.dbmigrate;

import software.amazon.awssdk.services.s3.S3Client;
import software.amazon.awssdk.services.ssm.SsmClient;

import java.io.IOException;
import java.io.InputStream;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.SQLException;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.Map;

import static uk.gov.companieshouse.addresslookup.dbmigrate.MigrationException.Code.BUNDLE_CHECKSUM_MISMATCH;
import static uk.gov.companieshouse.addresslookup.dbmigrate.MigrationException.Code.REPAIR_DISABLED;

/** Fetch the pinned snapshot, resolve credentials, run one Flyway command, report what happened. */
public final class MigrationService {
  private final MigrationConfig config;
  private final BundleSource bundles;
  private final CredentialsSource credentials;
  private final ConnectionFactory connections;
  private final FlywayRunner runner;

  public MigrationService(
      MigrationConfig config,
      BundleSource bundles,
      CredentialsSource credentials,
      ConnectionFactory connections,
      FlywayRunner runner) {
    this.config = config;
    this.bundles = bundles;
    this.credentials = credentials;
    this.connections = connections;
    this.runner = runner;
  }

  public static MigrationService fromEnvironment() {
    return new MigrationService(
        MigrationConfig.fromEnv(System.getenv()),
        new S3BundleSource(S3Client.create()),
        new ParameterStoreCredentialsSource(SsmClient.create()),
        ConnectionFactory.jdbc(),
        new FlywayRunner());
  }

  public Map<String, Object> handle(MigrationRequest request) {
    if (request.command() == Command.REPAIR && !config.allowRepair())
      throw new MigrationException(
          REPAIR_DISABLED, "repair is disabled; set ALLOW_REPAIR=true for this environment");
    long started = System.nanoTime();
    Path work = createWorkDir();
    try {
      SnapshotBundle.Extracted bundle;
      try (InputStream in = bundles.open(config.snapshotBucket(), request.key(), request.versionId())) {
        bundle = SnapshotBundle.extract(in, work, config.maxBundleBytes(), config.maxBundleEntries());
      } catch (IOException e) {
        throw new UncheckedIOException("Cannot read snapshot", e);
      }
      // The bytes must be the ones the pipeline gathered from the tagged commit.
      if (!bundle.sha256().equals(request.sha256()))
        throw new MigrationException(
            BUNDLE_CHECKSUM_MISMATCH,
            request.key() + " has SHA-256 " + bundle.sha256() + ", expected " + request.sha256());

      DatabaseCredentials db = credentials.get(config.credentialsParameter());
      probe(db);
      FlywayRunner.Outcome outcome =
          runner.run(request.command(), bundle.migrations(), () -> connections.open(db), config.lockTimeout());
      return result(request, bundle, outcome, (System.nanoTime() - started) / 1_000_000);
    } finally {
      deleteQuietly(work);
    }
  }

  /** Fail with DATABASE_UNAVAILABLE before Flyway starts, rather than inside its error handling. */
  private void probe(DatabaseCredentials db) {
    try (var connection = connections.open(db)) {
      connection.isValid(5);
    } catch (SQLException ignored) {
      // Opened successfully; a failure closing the probe doesn't matter.
    }
  }

  private Map<String, Object> result(
      MigrationRequest request, SnapshotBundle.Extracted bundle, FlywayRunner.Outcome outcome, long millis) {
    var result = new LinkedHashMap<String, Object>();
    result.put("status", "SUCCESS");
    result.put("command", request.command().wireName());
    result.put(
        "snapshot",
        Map.of(
            "bucket", config.snapshotBucket(),
            "key", request.key(),
            "versionId", request.versionId(),
            "sha256", bundle.sha256(),
            "bytes", bundle.zipBytes(),
            "files", bundle.files()));
    result.put("schemaVersion", outcome.current());
    switch (request.command()) {
      case MIGRATE -> result.put("applied", outcome.applied());
      case REPAIR -> result.put("repaired", outcome.repaired());
      case INFO -> result.put("pending", outcome.pending());
      case VALIDATE -> {
        result.put("valid", true);
        result.put("pending", outcome.pending());
      }
    }
    result.put("durationMs", millis);
    return result;
  }

  private static Path createWorkDir() {
    try {
      return Files.createTempDirectory("db-migrate-");
    } catch (IOException e) {
      throw new UncheckedIOException(e);
    }
  }

  /** Warm execution environments reuse /tmp, so always clean up. */
  private static void deleteQuietly(Path dir) {
    try (var paths = Files.walk(dir)) {
      paths.sorted(Comparator.reverseOrder()).forEach(p -> p.toFile().delete());
    } catch (IOException ignored) {
      // Best effort: the directory name is unique per invocation.
    }
  }
}
