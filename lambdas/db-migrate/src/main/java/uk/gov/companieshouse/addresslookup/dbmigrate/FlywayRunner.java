package uk.gov.companieshouse.addresslookup.dbmigrate;

import org.flywaydb.core.Flyway;
import org.flywaydb.core.api.FlywayException;
import org.flywaydb.core.api.MigrationInfo;
import org.flywaydb.core.api.configuration.FluentConfiguration;
import org.flywaydb.core.api.exception.FlywayValidateException;
import org.flywaydb.database.postgresql.PostgreSQLConfigurationExtension;

import javax.sql.DataSource;
import java.io.PrintWriter;
import java.nio.file.Path;
import java.sql.Connection;
import java.sql.SQLFeatureNotSupportedException;
import java.util.Arrays;
import java.util.List;
import java.util.function.Supplier;
import java.util.logging.Logger;

import static uk.gov.companieshouse.addresslookup.dbmigrate.MigrationException.Code.MIGRATION_FAILED;
import static uk.gov.companieshouse.addresslookup.dbmigrate.MigrationException.Code.VALIDATION_FAILED;

/**
 * Thin wrapper over the Flyway Community API. Flyway's lock on its schema history table
 * serialises concurrent runs, and each migration script runs in its own transaction.
 */
public final class FlywayRunner {

  /**
   * Migrations are named by their script file, e.g. {@code V2026.003__create_tables.sql}.
   * {@code current} is the schema version after the command ran (null on an empty database).
   */
  public record Outcome(String current, List<String> pending, List<String> applied, List<String> repaired) {}

  public Outcome run(Command command, Path migrations, Supplier<Connection> connections, String lockTimeout) {
    FluentConfiguration configuration =
        Flyway.configure(FlywayRunner.class.getClassLoader())
            .dataSource(new FactoryDataSource(connections))
            .locations("filesystem:" + migrations.toAbsolutePath())
            .failOnMissingLocations(true)
            // Flyway silently skips files it can't parse as migrations; fail instead.
            .validateMigrationNaming(true)
            .cleanDisabled(true)
            .baselineOnMigrate(false)
            .outOfOrder(false)
            .connectRetries(0)
            .initSql("SET lock_timeout = '" + lockTimeout + "'")
            // validate checks what has been applied; pending migrations are not an error.
            .ignoreMigrationPatterns(command == Command.VALIDATE ? "*:pending" : "*:future");
    // Flyway's default lock is held inside a transaction, which CREATE INDEX CONCURRENTLY waits
    // for, so a concurrent index migration would otherwise block on Flyway itself.
    configuration.getConfigurationExtension(PostgreSQLConfigurationExtension.class).setTransactionalLock(false);
    Flyway flyway = configuration.load();
    try {
      return switch (command) {
        case INFO -> {
          var info = flyway.info();
          yield new Outcome(version(info.current()), scripts(info.pending()), List.of(), List.of());
        }
        case VALIDATE -> {
          var result = flyway.validateWithResult();
          if (!result.validationSuccessful)
            throw new MigrationException(VALIDATION_FAILED, result.getAllErrorMessages());
          var info = flyway.info();
          yield new Outcome(version(info.current()), scripts(info.pending()), List.of(), List.of());
        }
        case MIGRATE -> {
          var result = flyway.migrate();
          var applied =
              result.migrations.stream().map(m -> Path.of(m.filepath).getFileName().toString()).toList();
          yield new Outcome(version(flyway.info().current()), List.of(), applied, List.of());
        }
        case REPAIR -> {
          var result = flyway.repair();
          yield new Outcome(version(flyway.info().current()), List.of(), List.of(), List.copyOf(result.repairActions));
        }
      };
    } catch (MigrationException e) {
      throw e;
    } catch (FlywayValidateException e) {
      throw new MigrationException(VALIDATION_FAILED, e.getMessage(), e);
    } catch (FlywayException e) {
      // A connection failure surfaces from inside Flyway; keep its own code.
      for (Throwable cause = e.getCause(); cause != null; cause = cause.getCause())
        if (cause instanceof MigrationException m) throw m;
      throw new MigrationException(MIGRATION_FAILED, e.getMessage(), e);
    }
  }

  private static String version(MigrationInfo info) {
    return info == null || info.getVersion() == null ? null : info.getVersion().getVersion();
  }

  private static List<String> scripts(MigrationInfo[] migrations) {
    return Arrays.stream(migrations).map(MigrationInfo::getScript).toList();
  }

  /** Each getConnection opens a fresh connection; Flyway closes what it opens. */
  private record FactoryDataSource(Supplier<Connection> connections) implements DataSource {
    @Override
    public Connection getConnection() {
      return connections.get();
    }

    @Override
    public Connection getConnection(String username, String password) {
      return connections.get();
    }

    @Override
    public PrintWriter getLogWriter() {
      return null;
    }

    @Override
    public void setLogWriter(PrintWriter out) {}

    @Override
    public void setLoginTimeout(int seconds) {}

    @Override
    public int getLoginTimeout() {
      return 0;
    }

    @Override
    public Logger getParentLogger() throws SQLFeatureNotSupportedException {
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
