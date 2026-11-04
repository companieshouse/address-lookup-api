package uk.gov.companieshouse.addresslookup.dbmigrate;

import org.flywaydb.core.Flyway;
import org.flywaydb.core.api.FlywayException;
import org.flywaydb.core.api.MigrationInfo;
import org.flywaydb.core.api.configuration.FluentConfiguration;
import org.flywaydb.core.api.exception.FlywayValidateException;
import org.flywaydb.core.api.output.RepairResult;
import org.flywaydb.database.postgresql.PostgreSQLConfigurationExtension;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import javax.sql.DataSource;
import java.io.PrintWriter;
import java.nio.file.Path;
import java.sql.Connection;
import java.sql.SQLFeatureNotSupportedException;
import java.util.Arrays;
import java.util.List;
import java.util.Objects;
import java.util.function.Supplier;
import java.util.stream.Stream;

import static uk.gov.companieshouse.addresslookup.dbmigrate.MigrationException.Code.MIGRATION_FAILED;
import static uk.gov.companieshouse.addresslookup.dbmigrate.MigrationException.Code.VALIDATION_FAILED;

/**
 * Thin wrapper over the Flyway Community API. Flyway's lock on its schema history table
 * serialises concurrent runs, and each migration script runs in its own transaction.
 */
public final class FlywayRunner {
  private static final Logger LOG = LoggerFactory.getLogger(FlywayRunner.class);

  /**
   * Migrations are named by their script file, e.g. {@code V2026.003__create_tables.sql}.
   * {@code latestAppliedSchemaVersion} is the schema version after the command ran (null on an empty database).
   * {@code repairActions} is the description of what Flyway did during a repair, such as aligning checksums.
   */
  public record Outcome(
      String latestAppliedSchemaVersion,
      List<String> pendingScriptFileNames,
      List<String> appliedScriptFileNames,
      List<String> repairedScriptFileNames,
      List<String> repairActions) {}

  public Outcome run(
      Command command, Path migrationsDir, Supplier<Connection> dbConnectionSupplier, String lockTimeout) {
    FluentConfiguration configuration =
        Flyway.configure(FlywayRunner.class.getClassLoader())
            .dataSource(new FactoryDataSource(dbConnectionSupplier))
            .locations("filesystem:" + migrationsDir.toAbsolutePath())
            .failOnMissingLocations(true)
            // Flyway silently skips files it cannot parse as migrations. Fail instead.
            .validateMigrationNaming(true)
            .cleanDisabled(true)
            .baselineOnMigrate(false)
            .outOfOrder(false)
            .connectRetries(0)
            .initSql("SET lock_timeout = '" + lockTimeout + "'")
            // The validate command checks only what has been applied, so pending migrations are not an error.
            .ignoreMigrationPatterns(command == Command.VALIDATE ? "*:pending" : "*:future");
    // By default, Flyway holds its lock inside a transaction that stays open for the whole migration.
    // CREATE INDEX CONCURRENTLY must wait for every older open transaction to finish,
    // so it would wait for Flyway's own lock transaction, which in turn waits for the migration.
    // Use a session-level lock instead, so there is no long-running transaction to wait for.
    // Concurrent runs are still serialised by the lock.
    configuration.getConfigurationExtension(PostgreSQLConfigurationExtension.class).setTransactionalLock(false);
    Flyway flyway = configuration.load();
    LOG.info("Flyway {} using migrations in {}", command.wireName(), migrationsDir);
    try {
      Outcome outcome = executeFlywayCommand(command, flyway);
      LOG.info("Flyway {} finished at schema version {}", command.wireName(), outcome.latestAppliedSchemaVersion());
      return outcome;
    } catch (MigrationException e) {
      // Rethrow as is, so the FlywayException branch below does not wrap it.
      throw e;
    } catch (FlywayValidateException e) {
      throw new MigrationException(VALIDATION_FAILED, e.getMessage(), e);
    } catch (FlywayException e) {
      // The connection supplier throws a MigrationException when it cannot connect to the database.
      // Flyway wraps that exception inside a FlywayException, so the original error would be hidden.
      // Walk the cause chain to find it and rethrow it, so the caller sees its own error code
      // instead of a generic MIGRATION_FAILED.
      for (Throwable cause = e.getCause(); cause != null; cause = cause.getCause())
        if (cause instanceof MigrationException m) throw m;
      throw new MigrationException(MIGRATION_FAILED, e.getMessage(), e);
    }
  }

  private static Outcome executeFlywayCommand(Command command, Flyway flyway) {
    return switch (command) {
      case INFO -> {
        var migrationInfoService = flyway.info();
        yield new Outcome(
            getLatestAppliedSchemaVersionOrNull(migrationInfoService.current()),
            getPendingScriptFileNamesFromMigrationInfos(migrationInfoService.pending()),
            List.of(),
            List.of(),
            List.of());
      }
      case VALIDATE -> {
        var validateResult = flyway.validateWithResult();
        if (!validateResult.validationSuccessful)
          throw new MigrationException(VALIDATION_FAILED, validateResult.getAllErrorMessages());
        var migrationInfoService = flyway.info();
        yield new Outcome(
            getLatestAppliedSchemaVersionOrNull(migrationInfoService.current()),
            getPendingScriptFileNamesFromMigrationInfos(migrationInfoService.pending()),
            List.of(),
            List.of(),
            List.of());
      }
      case MIGRATE -> {
        var migrateResult = flyway.migrate();
        var appliedScriptFileNames =
            migrateResult.migrations.stream()
                .map(appliedMigration -> Path.of(appliedMigration.filepath).getFileName().toString())
                .toList();
        yield new Outcome(
            getLatestAppliedSchemaVersionOrNull(flyway.info().current()),
            List.of(),
            appliedScriptFileNames,
            List.of(),
            List.of());
      }
      case REPAIR -> {
        var repairResult = flyway.repair();
        yield new Outcome(
            getLatestAppliedSchemaVersionOrNull(flyway.info().current()),
            List.of(),
            List.of(),
            getRepairedScriptFileNamesFromRepairResult(repairResult),
            getRepairActionsFromRepairResult(repairResult));
      }
    };
  }

  /** Returns null when no migration has been applied yet, for example on an empty database. */
  private static String getLatestAppliedSchemaVersionOrNull(MigrationInfo latestAppliedMigrationInfo) {
    return latestAppliedMigrationInfo == null || latestAppliedMigrationInfo.getVersion() == null
        ? null
        : latestAppliedMigrationInfo.getVersion().getVersion();
  }

  /**
   * Returns the script file names of the migrations that the repair removed, deleted or aligned.
   * An entry without a file path, such as a failed migration that only exists in the schema history, is left out.
   * Use {@link #getRepairActionsFromRepairResult} to see that something happened to such an entry.
   */
  private static List<String> getRepairedScriptFileNamesFromRepairResult(RepairResult repairResult) {
    return Stream.of(repairResult.migrationsRemoved, repairResult.migrationsDeleted, repairResult.migrationsAligned)
        .filter(Objects::nonNull)
        .flatMap(List::stream)
        .map(repairedMigration -> repairedMigration.filepath)
        .filter(Objects::nonNull)
        .map(filepath -> Path.of(filepath).getFileName().toString())
        .distinct()
        .toList();
  }

  /** Returns the descriptions of what Flyway did during the repair, or an empty list when it did nothing. */
  private static List<String> getRepairActionsFromRepairResult(RepairResult repairResult) {
    return repairResult.repairActions == null ? List.of() : List.copyOf(repairResult.repairActions);
  }

  /** Returns the script file names (for example {@code V2026.004__create_address_lookup_view.sql}) of migrations not yet applied. */
  private static List<String> getPendingScriptFileNamesFromMigrationInfos(MigrationInfo[] pendingMigrationInfos) {
    return Arrays.stream(pendingMigrationInfos).map(MigrationInfo::getScript).toList();
  }

  /** Each getConnection call opens a fresh connection, and Flyway closes every connection it opens. */
  private record FactoryDataSource(Supplier<Connection> dbConnectionSupplier) implements DataSource {
    @Override
    public Connection getConnection() {
      return dbConnectionSupplier.get();
    }

    @Override
    public Connection getConnection(String username, String password) {
      return dbConnectionSupplier.get();
    }

    @Override
    public PrintWriter getLogWriter() {
      return null;
    }

    @Override
    public void setLogWriter(PrintWriter out) {
      // Intentionally empty because Flyway does not use the data source log writer
    }

    @Override
    public void setLoginTimeout(int seconds) {
      // Intentionally empty because the connection supplier decides the connect timeout
    }

    @Override
    public int getLoginTimeout() {
      return 0;
    }

    @Override
    public java.util.logging.Logger getParentLogger() throws SQLFeatureNotSupportedException {
      throw new SQLFeatureNotSupportedException();
    }

    @Override
    public <T> T unwrap(Class<T> iface) throws java.sql.SQLException {
      throw new java.sql.SQLException("Not a wrapper");
    }

    @Override
    public boolean isWrapperFor(Class<?> iface) {
      return false;
    }
  }
}
