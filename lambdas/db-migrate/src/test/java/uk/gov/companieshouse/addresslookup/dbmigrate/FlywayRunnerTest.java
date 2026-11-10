package uk.gov.companieshouse.addresslookup.dbmigrate;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.Connection;
import java.sql.SQLException;
import java.sql.SQLFeatureNotSupportedException;
import java.util.List;
import java.util.function.Supplier;
import javax.sql.DataSource;
import org.flywaydb.core.Flyway;
import org.flywaydb.core.api.ErrorCode;
import org.flywaydb.core.api.ErrorDetails;
import org.flywaydb.core.api.FlywayException;
import org.flywaydb.core.api.MigrationInfo;
import org.flywaydb.core.api.MigrationInfoService;
import org.flywaydb.core.api.MigrationVersion;
import org.flywaydb.core.api.configuration.FluentConfiguration;
import org.flywaydb.core.api.exception.FlywayValidateException;
import org.flywaydb.core.api.output.MigrateOutput;
import org.flywaydb.core.api.output.MigrateResult;
import org.flywaydb.core.api.output.RepairOutput;
import org.flywaydb.core.api.output.RepairResult;
import org.flywaydb.core.api.output.ValidateResult;
import org.flywaydb.database.postgresql.PostgreSQLConfigurationExtension;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;
import org.mockito.Answers;
import org.mockito.ArgumentCaptor;
import org.mockito.ArgumentMatchers;
import org.mockito.Mock;
import org.mockito.MockedStatic;
import org.mockito.Mockito;
import org.mockito.junit.jupiter.MockitoExtension;

/**
 * Unit tests for FlywayRunner. Flyway itself is mocked, so these cover how the runner configures
 * Flyway, builds the outcome for each command and maps failures to error codes. Running real
 * migrations needs a database, so that is covered by MigrationServiceIT.
 */
@ExtendWith(MockitoExtension.class)
class FlywayRunnerTest {

  private static final String LOCK_TIMEOUT = "10s";

  @TempDir Path migrationsDir;

  @Mock(answer = Answers.RETURNS_SELF)
  private FluentConfiguration fluentConfiguration;

  @Mock private PostgreSQLConfigurationExtension postgresConfigurationExtension;
  @Mock private Flyway flyway;
  @Mock private MigrationInfoService migrationInfoService;
  @Mock private Supplier<Connection> dbConnectionSupplier;
  @Mock private Connection dbConnection;

  private final FlywayRunner flywayRunner = new FlywayRunner();

  @BeforeEach
  void writeOneMigration() throws IOException {
    Files.writeString(migrationsDir.resolve("V1__create_table.sql"), "CREATE TABLE t (id int);");
  }

  /** Runs the runner with Flyway.configure replaced, so no database or real Flyway is involved. */
  private FlywayRunner.Outcome runWithMockedFlyway(Command command) {
    try (MockedStatic<Flyway> flywayStatic = Mockito.mockStatic(Flyway.class)) {
      flywayStatic
          .when(() -> Flyway.configure(ArgumentMatchers.any(ClassLoader.class)))
          .thenReturn(fluentConfiguration);
      when(fluentConfiguration.getConfigurationExtension(PostgreSQLConfigurationExtension.class))
          .thenReturn(postgresConfigurationExtension);
      when(fluentConfiguration.load()).thenReturn(flyway);
      return flywayRunner.run(command, migrationsDir, dbConnectionSupplier, LOCK_TIMEOUT);
    }
  }

  /** For migrate and repair, which only read the current schema version. */
  private void stubCurrent(MigrationInfo current) {
    when(flyway.info()).thenReturn(migrationInfoService);
    when(migrationInfoService.current()).thenReturn(current);
  }

  /** For info and validate, which also read the pending migrations. */
  private void stubInfo(MigrationInfo current, MigrationInfo... pending) {
    stubCurrent(current);
    when(migrationInfoService.pending()).thenReturn(pending);
  }

  private static MigrationInfo appliedMigrationInfo(String version) {
    var migrationInfo = Mockito.mock(MigrationInfo.class);
    when(migrationInfo.getVersion()).thenReturn(MigrationVersion.fromVersion(version));
    return migrationInfo;
  }

  private static MigrationInfo pendingMigrationInfo(String script) {
    var migrationInfo = Mockito.mock(MigrationInfo.class);
    when(migrationInfo.getScript()).thenReturn(script);
    return migrationInfo;
  }

  private static MigrateOutput migrateOutput(String filepath) {
    var output = new MigrateOutput();
    output.filepath = filepath;
    return output;
  }

  private static RepairOutput repairOutput(String filepath) {
    return new RepairOutput("1", "description", filepath);
  }

  // ---- Real Flyway: connection failures ----

  @ParameterizedTest
  @EnumSource(Command.class)
  void connectionFailureKeepsItsOwnErrorCode(Command command) {
    var connectionFailure =
        new MigrationException(MigrationException.Code.DATABASE_UNAVAILABLE, "no route to host");
    Supplier<Connection> failingConnectionSupplier =
        () -> {
          throw connectionFailure;
        };

    var e =
        assertThrows(
            MigrationException.class,
            () ->
                flywayRunner.run(command, migrationsDir, failingConnectionSupplier, LOCK_TIMEOUT));

    assertEquals(MigrationException.Code.DATABASE_UNAVAILABLE, e.code());
    assertSame(connectionFailure, e);
  }

  // ---- Flyway configuration ----

  @Test
  void configuresFlywayForASafeRun() {
    stubInfo(null);

    runWithMockedFlyway(Command.INFO);

    verify(fluentConfiguration).locations("filesystem:" + migrationsDir.toAbsolutePath());
    verify(fluentConfiguration).failOnMissingLocations(true);
    verify(fluentConfiguration).validateMigrationNaming(true);
    verify(fluentConfiguration).cleanDisabled(true);
    verify(fluentConfiguration).baselineOnMigrate(false);
    verify(fluentConfiguration).outOfOrder(false);
    verify(fluentConfiguration).connectRetries(0);
    verify(fluentConfiguration).initSql("SET lock_timeout = '10s'");
    verify(fluentConfiguration).ignoreMigrationPatterns("*:future");
    verify(postgresConfigurationExtension).setTransactionalLock(false);
  }

  @Test
  void dataSourceHandsOutConnectionsFromTheSupplier() throws Exception {
    stubInfo(null);
    when(dbConnectionSupplier.get()).thenReturn(dbConnection);

    runWithMockedFlyway(Command.INFO);

    var dataSourceCaptor = ArgumentCaptor.forClass(DataSource.class);
    verify(fluentConfiguration).dataSource(dataSourceCaptor.capture());
    var dataSource = dataSourceCaptor.getValue();
    assertSame(dbConnection, dataSource.getConnection());
    assertSame(dbConnection, dataSource.getConnection("ignored-user", "ignored-password"));
    assertNull(dataSource.getLogWriter());
    dataSource.setLogWriter(null);
    dataSource.setLoginTimeout(30);
    assertEquals(0, dataSource.getLoginTimeout());
    assertFalse(dataSource.isWrapperFor(DataSource.class));
    assertThrows(SQLException.class, () -> dataSource.unwrap(DataSource.class));
    assertThrows(SQLFeatureNotSupportedException.class, dataSource::getParentLogger);
  }

  // ---- info ----

  @Test
  void infoReportsTheCurrentSchemaVersionAndThePendingScriptFileNames() {
    stubInfo(
        appliedMigrationInfo("2026.003"),
        pendingMigrationInfo("V2026.004__create_view.sql"),
        pendingMigrationInfo("V2026.005__add_index.sql"));

    var outcome = runWithMockedFlyway(Command.INFO);

    assertEquals("2026.003", outcome.latestAppliedSchemaVersion());
    assertEquals(
        List.of("V2026.004__create_view.sql", "V2026.005__add_index.sql"),
        outcome.pendingScriptFileNames());
    assertEquals(List.of(), outcome.appliedScriptFileNames());
    assertEquals(List.of(), outcome.repairedScriptFileNames());
    assertEquals(List.of(), outcome.repairActions());
  }

  @Test
  void infoOnAnEmptyDatabaseHasNoSchemaVersion() {
    stubInfo(null, pendingMigrationInfo("V2026.001__create_schemas.sql"));

    var outcome = runWithMockedFlyway(Command.INFO);

    assertNull(outcome.latestAppliedSchemaVersion());
    assertEquals(List.of("V2026.001__create_schemas.sql"), outcome.pendingScriptFileNames());
  }

  @Test
  void infoTreatsACurrentMigrationWithoutAVersionAsNoSchemaVersion() {
    stubInfo(Mockito.mock(MigrationInfo.class));

    var outcome = runWithMockedFlyway(Command.INFO);

    assertNull(outcome.latestAppliedSchemaVersion());
    assertEquals(List.of(), outcome.pendingScriptFileNames());
  }

  // ---- validate ----

  @Test
  void validateReportsThePendingScriptFileNamesAndIgnoresPendingMigrations() {
    when(flyway.validateWithResult())
        .thenReturn(new ValidateResult("13.10.0", "db", null, true, 3, List.of(), List.of()));
    stubInfo(appliedMigrationInfo("2026.003"), pendingMigrationInfo("V2026.004__create_view.sql"));

    var outcome = runWithMockedFlyway(Command.VALIDATE);

    assertEquals("2026.003", outcome.latestAppliedSchemaVersion());
    assertEquals(List.of("V2026.004__create_view.sql"), outcome.pendingScriptFileNames());
    verify(fluentConfiguration).ignoreMigrationPatterns("*:pending");
  }

  @Test
  void validateFailureFailsAsValidationFailed() {
    var failedValidation = Mockito.mock(ValidateResult.class);
    when(failedValidation.getAllErrorMessages()).thenReturn("Checksum mismatch for V2026.001");
    when(flyway.validateWithResult()).thenReturn(failedValidation);

    var e = assertThrows(MigrationException.class, () -> runWithMockedFlyway(Command.VALIDATE));

    assertEquals(MigrationException.Code.VALIDATION_FAILED, e.code());
    assertEquals("VALIDATION_FAILED: Checksum mismatch for V2026.001", e.getMessage());
  }

  // ---- migrate ----

  @Test
  void migrateReportsTheAppliedScriptFileNamesAndTheNewSchemaVersion() {
    var migrateResult = new MigrateResult();
    migrateResult.migrations =
        List.of(
            migrateOutput("/tmp/db-migrate-1/root/migrations/V2026.001__create_schemas.sql"),
            migrateOutput("/tmp/db-migrate-1/root/migrations/V2026.002__create_tables.sql"));
    when(flyway.migrate()).thenReturn(migrateResult);
    stubCurrent(appliedMigrationInfo("2026.002"));

    var outcome = runWithMockedFlyway(Command.MIGRATE);

    assertEquals("2026.002", outcome.latestAppliedSchemaVersion());
    assertEquals(
        List.of("V2026.001__create_schemas.sql", "V2026.002__create_tables.sql"),
        outcome.appliedScriptFileNames());
    assertEquals(List.of(), outcome.pendingScriptFileNames());
    verify(fluentConfiguration).ignoreMigrationPatterns("*:future");
  }

  @Test
  void migrateWithNothingToApplyReportsNoAppliedScripts() {
    var migrateResult = new MigrateResult();
    migrateResult.migrations = List.of();
    when(flyway.migrate()).thenReturn(migrateResult);
    stubCurrent(appliedMigrationInfo("2026.004"));

    var outcome = runWithMockedFlyway(Command.MIGRATE);

    assertEquals("2026.004", outcome.latestAppliedSchemaVersion());
    assertEquals(List.of(), outcome.appliedScriptFileNames());
  }

  // ---- repair ----

  @Test
  void repairReportsTheDistinctRepairedScriptFileNamesAndTheRepairActions() {
    var repairResult = new RepairResult("13.10.0", "db");
    repairResult.migrationsRemoved = List.of(repairOutput("/tmp/m/V1__a.sql"), repairOutput(null));
    repairResult.migrationsDeleted = List.of(repairOutput("/tmp/m/V2__b.sql"));
    repairResult.migrationsAligned = List.of(repairOutput("/tmp/other/V1__a.sql"));
    repairResult.repairActions = List.of("Removed failed migration V1", "Aligned checksum of V2");
    when(flyway.repair()).thenReturn(repairResult);
    stubCurrent(appliedMigrationInfo("2026.003"));

    var outcome = runWithMockedFlyway(Command.REPAIR);

    assertEquals("2026.003", outcome.latestAppliedSchemaVersion());
    assertEquals(List.of("V1__a.sql", "V2__b.sql"), outcome.repairedScriptFileNames());
    assertEquals(
        List.of("Removed failed migration V1", "Aligned checksum of V2"), outcome.repairActions());
    assertEquals(List.of(), outcome.appliedScriptFileNames());
    assertEquals(List.of(), outcome.pendingScriptFileNames());
  }

  @Test
  void repairWithNothingToDoReportsEmptyLists() {
    var repairResult = new RepairResult("13.10.0", "db");
    repairResult.migrationsRemoved = null;
    repairResult.migrationsDeleted = null;
    repairResult.migrationsAligned = null;
    repairResult.repairActions = null;
    when(flyway.repair()).thenReturn(repairResult);
    stubCurrent(null);

    var outcome = runWithMockedFlyway(Command.REPAIR);

    assertNull(outcome.latestAppliedSchemaVersion());
    assertEquals(List.of(), outcome.repairedScriptFileNames());
    assertEquals(List.of(), outcome.repairActions());
  }

  // ---- failures ----

  @Test
  void flywayValidateExceptionFailsAsValidationFailed() {
    var validateException =
        new FlywayValidateException(
            new ErrorDetails(Mockito.mock(ErrorCode.class), "checksum mismatch"),
            "checksum mismatch");
    when(flyway.migrate()).thenThrow(validateException);

    var e = assertThrows(MigrationException.class, () -> runWithMockedFlyway(Command.MIGRATE));

    assertEquals(MigrationException.Code.VALIDATION_FAILED, e.code());
    assertSame(validateException, e.getCause());
  }

  @Test
  void otherFlywayExceptionsFailAsMigrationFailed() {
    var flywayException = new FlywayException("Migration V2026.002 failed");
    when(flyway.migrate()).thenThrow(flywayException);

    var e = assertThrows(MigrationException.class, () -> runWithMockedFlyway(Command.MIGRATE));

    assertEquals(MigrationException.Code.MIGRATION_FAILED, e.code());
    assertEquals("MIGRATION_FAILED: Migration V2026.002 failed", e.getMessage());
    assertSame(flywayException, e.getCause());
  }

  @Test
  void migrationExceptionWrappedDeepInsideFlywayExceptionIsRethrown() {
    var databaseUnavailable =
        new MigrationException(MigrationException.Code.DATABASE_UNAVAILABLE, "connection reset");
    when(flyway.info())
        .thenThrow(
            new FlywayException(
                "Unable to obtain connection",
                new RuntimeException("wrapper", databaseUnavailable)));

    var e = assertThrows(MigrationException.class, () -> runWithMockedFlyway(Command.INFO));

    assertSame(databaseUnavailable, e);
  }

  @Test
  void migrationExceptionFromFlywayIsRethrownUnchanged() {
    var migrationException =
        new MigrationException(MigrationException.Code.DATABASE_UNAVAILABLE, "lock timeout");
    when(flyway.migrate()).thenThrow(migrationException);

    var e = assertThrows(MigrationException.class, () -> runWithMockedFlyway(Command.MIGRATE));

    assertSame(migrationException, e);
  }
}
