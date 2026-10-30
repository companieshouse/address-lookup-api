package uk.gov.companieshouse.addresslookup.dbmigrate;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.sql.Connection;
import java.util.HexFormat;
import java.util.List;
import java.util.Map;
import java.util.function.Supplier;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

/** Ordering guarantees that need no database: what is checked before anything is touched. */
@ExtendWith(MockitoExtension.class)
class MigrationServiceTest {

  private static final String SNAPSHOT_BUCKET = "snapshot-bucket";
  private static final String DB_CREDENTIALS_PARAMETER_PATH = "/address-lookup/test/db/schema-owner";
  private static final String SNAPSHOT_KEY = "address-lookup/db-schema-snapshots/x.zip";
  private static final String VERSION_ID = "v1";
  private static final DatabaseCredentials DB_CREDENTIALS =
      new DatabaseCredentials("jdbc:postgresql://localhost:5432/db", "user", "secret");

  @Mock private BundleSource s3BundlesSource;
  @Mock private DbCredentialsSource dbCredentialsSource;
  @Mock private ConnectionFactory dbConnectionFactory;
  @Mock private Connection dbConnection;
  @Mock private FlywayRunner flywayRunner;

  private MigrationService service;

  @BeforeEach
  void setup() {
    service = buildService(false);
  }

  private MigrationService buildService(boolean allowRepair) {
    var migrationConfig =
        new MigrationConfig(SNAPSHOT_BUCKET, DB_CREDENTIALS_PARAMETER_PATH, allowRepair, "10s", 1_000_000, 100);
    return new MigrationService(
        migrationConfig, s3BundlesSource, dbCredentialsSource, dbConnectionFactory, flywayRunner);
  }

  @Test
  void checksumMismatchFailsBeforeCredentialsOrDatabase() throws Exception {
    byte[] snapshot = SnapshotZipExtractorTest.buildZipBytes(Map.of("migrations/V1__x.sql", "SELECT 1;"));
    when(s3BundlesSource.open(SNAPSHOT_BUCKET, SNAPSHOT_KEY, VERSION_ID))
        .thenReturn(new ByteArrayInputStream(snapshot));
    var request = new MigrationRequest(Command.MIGRATE, SNAPSHOT_KEY, VERSION_ID, "0".repeat(64));

    var e = assertThrows(MigrationException.class, () -> service.handle(request));

    assertEquals(MigrationException.Code.BUNDLE_CHECKSUM_MISMATCH, e.code());
    verifyNoInteractions(dbCredentialsSource, dbConnectionFactory);
  }

  @Test
  void repairIsRefusedUnlessAllowedWithoutReadingAnything() {
    var request = new MigrationRequest(Command.REPAIR, SNAPSHOT_KEY, VERSION_ID, "0".repeat(64));

    var e = assertThrows(MigrationException.class, () -> service.handle(request));

    assertEquals(MigrationException.Code.REPAIR_DISABLED, e.code());
    verifyNoInteractions(s3BundlesSource, dbCredentialsSource, dbConnectionFactory);
  }

  @Test
  void invalidDbConnectionFailsAsDatabaseUnavailableAndIsClosed() throws Exception {
    byte[] snapshot = SnapshotZipExtractorTest.buildZipBytes(Map.of("migrations/V1__x.sql", "SELECT 1;"));
    String snapshotSha256 = HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(snapshot));
    var request = new MigrationRequest(Command.MIGRATE, SNAPSHOT_KEY, VERSION_ID, snapshotSha256);
    var dbCredentials = new DatabaseCredentials("jdbc:postgresql://localhost:5432/db", "user", "secret");
    when(s3BundlesSource.open(SNAPSHOT_BUCKET, SNAPSHOT_KEY, VERSION_ID))
        .thenReturn(new ByteArrayInputStream(snapshot));
    when(dbCredentialsSource.get(DB_CREDENTIALS_PARAMETER_PATH)).thenReturn(dbCredentials);
    when(dbConnectionFactory.open(dbCredentials)).thenReturn(dbConnection);
    when(dbConnection.isValid(anyInt())).thenReturn(false);

    var e = assertThrows(MigrationException.class, () -> service.handle(request));

    assertEquals(MigrationException.Code.DATABASE_UNAVAILABLE, e.code());
    verify(dbConnection).close();
  }

  // ---- Normal flow: what each command reports -----------------------------------------------

  @Test
  void migrateReportsTheAppliedScriptFileNamesAndTheSnapshot() throws Exception {
    byte[] snapshotZipBytes = buildSnapshotZipBytes();
    var request = stubFlowUpToFlyway(Command.MIGRATE, snapshotZipBytes);
    when(flywayRunner.run(eq(Command.MIGRATE), any(Path.class), any(), eq("10s")))
        .thenReturn(
            new FlywayRunner.Outcome(
                "2026.004", List.of(), List.of("V2026.003__a.sql", "V2026.004__b.sql"), List.of(), List.of()));

    var result = service.handle(request);

    assertEquals("SUCCESS", result.get("status"));
    assertEquals("migrate", result.get("command"));
    assertEquals("2026.004", result.get("schemaVersion"));
    assertEquals(List.of("V2026.003__a.sql", "V2026.004__b.sql"), result.get("applied"));
    assertFalse(result.containsKey("pending"));
    assertFalse(result.containsKey("repaired"));
    assertFalse(result.containsKey("valid"));
    assertTrue((Long) result.get("durationMs") >= 0);
    assertEquals(
        Map.of(
            "bucket", SNAPSHOT_BUCKET,
            "key", SNAPSHOT_KEY,
            "versionId", VERSION_ID,
            "sha256", calculateSha256Hex(snapshotZipBytes),
            "bytes", (long) snapshotZipBytes.length,
            "files", 1),
        result.get("snapshot"));
  }

  @Test
  void infoReportsThePendingScriptFileNamesAndNullSchemaVersionOnAnEmptyDatabase() throws Exception {
    var request = stubFlowUpToFlyway(Command.INFO, buildSnapshotZipBytes());
    when(flywayRunner.run(eq(Command.INFO), any(Path.class), any(), eq("10s")))
        .thenReturn(new FlywayRunner.Outcome(null, List.of("V1__x.sql"), List.of(), List.of(), List.of()));

    var result = service.handle(request);

    assertEquals("info", result.get("command"));
    assertTrue(result.containsKey("schemaVersion"));
    assertNull(result.get("schemaVersion"));
    assertEquals(List.of("V1__x.sql"), result.get("pending"));
    assertFalse(result.containsKey("applied"));
  }

  @Test
  void validateReportsValidAndThePendingScriptFileNames() throws Exception {
    var request = stubFlowUpToFlyway(Command.VALIDATE, buildSnapshotZipBytes());
    when(flywayRunner.run(eq(Command.VALIDATE), any(Path.class), any(), eq("10s")))
        .thenReturn(new FlywayRunner.Outcome("1", List.of("V2__y.sql"), List.of(), List.of(), List.of()));

    var result = service.handle(request);

    assertEquals("validate", result.get("command"));
    assertEquals(true, result.get("valid"));
    assertEquals(List.of("V2__y.sql"), result.get("pending"));
    assertEquals("1", result.get("schemaVersion"));
  }

  @Test
  void repairReportsTheRepairedScriptFileNamesAndTheRepairActions() throws Exception {
    var repairEnabledService = buildService(true);
    var request = stubFlowUpToFlyway(Command.REPAIR, buildSnapshotZipBytes());
    when(flywayRunner.run(eq(Command.REPAIR), any(Path.class), any(), eq("10s")))
        .thenReturn(
            new FlywayRunner.Outcome(
                "1", List.of(), List.of(), List.of("V1__x.sql"), List.of("Repaired Schema History table")));

    var result = repairEnabledService.handle(request);

    assertEquals("repair", result.get("command"));
    assertEquals(List.of("V1__x.sql"), result.get("repaired"));
    assertEquals(List.of("Repaired Schema History table"), result.get("repairActions"));
    assertFalse(result.containsKey("applied"));
  }

  @Test
  void flywayReceivesTheExtractedMigrationsAndAConnectionFromTheFactory() throws Exception {
    var request = stubFlowUpToFlyway(Command.MIGRATE, buildSnapshotZipBytes());
    when(flywayRunner.run(eq(Command.MIGRATE), any(Path.class), any(), eq("10s")))
        .thenAnswer(
            invocation -> {
              Path migrationsDir = invocation.getArgument(1);
              assertTrue(Files.isRegularFile(migrationsDir.resolve("V1__x.sql")));
              return new FlywayRunner.Outcome("1", List.of(), List.of("V1__x.sql"), List.of(), List.of());
            });

    service.handle(request);

    @SuppressWarnings("unchecked")
    ArgumentCaptor<Supplier<Connection>> dbConnectionSupplierCaptor = ArgumentCaptor.forClass(Supplier.class);
    verify(flywayRunner).run(eq(Command.MIGRATE), any(Path.class), dbConnectionSupplierCaptor.capture(), eq("10s"));
    assertSame(dbConnection, dbConnectionSupplierCaptor.getValue().get());
  }

  // ---- Failures are reported as they are, and nothing later runs -----------------------------

  @Test
  void missingSnapshotFailsAsBundleNotFoundBeforeCredentialsOrDatabase() throws Exception {
    var request = new MigrationRequest(Command.MIGRATE, SNAPSHOT_KEY, VERSION_ID, "0".repeat(64));
    var bundleNotFoundException = new MigrationException(MigrationException.Code.BUNDLE_NOT_FOUND, "missing");
    when(s3BundlesSource.open(SNAPSHOT_BUCKET, SNAPSHOT_KEY, VERSION_ID)).thenThrow(bundleNotFoundException);

    var thrownException = assertThrows(MigrationException.class, () -> service.handle(request));

    assertSame(bundleNotFoundException, thrownException);
    verifyNoInteractions(dbCredentialsSource, dbConnectionFactory, flywayRunner);
  }

  @Test
  void invalidCredentialsFailBeforeTheDatabaseIsOpened() throws Exception {
    byte[] snapshotZipBytes = buildSnapshotZipBytes();
    var request = new MigrationRequest(Command.MIGRATE, SNAPSHOT_KEY, VERSION_ID, calculateSha256Hex(snapshotZipBytes));
    var credentialsInvalidException = new MigrationException(MigrationException.Code.CREDENTIALS_INVALID, "bad");
    when(s3BundlesSource.open(SNAPSHOT_BUCKET, SNAPSHOT_KEY, VERSION_ID))
        .thenReturn(new ByteArrayInputStream(snapshotZipBytes));
    when(dbCredentialsSource.get(DB_CREDENTIALS_PARAMETER_PATH)).thenThrow(credentialsInvalidException);

    var thrownException = assertThrows(MigrationException.class, () -> service.handle(request));

    assertSame(credentialsInvalidException, thrownException);
    verifyNoInteractions(dbConnectionFactory, flywayRunner);
  }

  @Test
  void unreachableDatabaseFailsBeforeFlywayStarts() throws Exception {
    byte[] snapshotZipBytes = buildSnapshotZipBytes();
    var request = new MigrationRequest(Command.MIGRATE, SNAPSHOT_KEY, VERSION_ID, calculateSha256Hex(snapshotZipBytes));
    var databaseUnavailableException = new MigrationException(MigrationException.Code.DATABASE_UNAVAILABLE, "down");
    when(s3BundlesSource.open(SNAPSHOT_BUCKET, SNAPSHOT_KEY, VERSION_ID))
        .thenReturn(new ByteArrayInputStream(snapshotZipBytes));
    when(dbCredentialsSource.get(DB_CREDENTIALS_PARAMETER_PATH)).thenReturn(DB_CREDENTIALS);
    when(dbConnectionFactory.open(DB_CREDENTIALS)).thenThrow(databaseUnavailableException);

    var thrownException = assertThrows(MigrationException.class, () -> service.handle(request));

    assertSame(databaseUnavailableException, thrownException);
    verifyNoInteractions(flywayRunner);
  }

  @Test
  void flywayFailureIsRethrownUnchanged() throws Exception {
    var request = stubFlowUpToFlyway(Command.MIGRATE, buildSnapshotZipBytes());
    var migrationFailedException = new MigrationException(MigrationException.Code.MIGRATION_FAILED, "boom");
    when(flywayRunner.run(eq(Command.MIGRATE), any(Path.class), any(), eq("10s")))
        .thenThrow(migrationFailedException);

    var thrownException = assertThrows(MigrationException.class, () -> service.handle(request));

    assertSame(migrationFailedException, thrownException);
  }

  // ---- The temporary work directory is always removed ----------------------------------------

  @Test
  void tempWorkDirIsDeletedAfterASuccessfulRun() throws Exception {
    var request = stubFlowUpToFlyway(Command.MIGRATE, buildSnapshotZipBytes());
    when(flywayRunner.run(eq(Command.MIGRATE), any(Path.class), any(), eq("10s")))
        .thenReturn(new FlywayRunner.Outcome("1", List.of(), List.of("V1__x.sql"), List.of(), List.of()));

    service.handle(request);

    assertFalse(Files.exists(captureTempWorkDirPassedToFlyway(Command.MIGRATE)));
  }

  @Test
  void tempWorkDirIsDeletedWhenFlywayFails() throws Exception {
    var request = stubFlowUpToFlyway(Command.MIGRATE, buildSnapshotZipBytes());
    when(flywayRunner.run(eq(Command.MIGRATE), any(Path.class), any(), eq("10s")))
        .thenThrow(new MigrationException(MigrationException.Code.MIGRATION_FAILED, "boom"));

    assertThrows(MigrationException.class, () -> service.handle(request));

    assertFalse(Files.exists(captureTempWorkDirPassedToFlyway(Command.MIGRATE)));
  }

  // ---- Helpers --------------------------------------------------------------------------------

  private static byte[] buildSnapshotZipBytes() throws IOException {
    return SnapshotZipExtractorTest.buildZipBytes(Map.of("migrations/V1__x.sql", "SELECT 1;"));
  }

  private static String calculateSha256Hex(byte[] bytes) throws Exception {
    return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(bytes));
  }

  /** Stubs everything that runs before Flyway, and returns the request that matches the snapshot. */
  private MigrationRequest stubFlowUpToFlyway(Command command, byte[] snapshotZipBytes) throws Exception {
    when(s3BundlesSource.open(SNAPSHOT_BUCKET, SNAPSHOT_KEY, VERSION_ID))
        .thenReturn(new ByteArrayInputStream(snapshotZipBytes));
    when(dbCredentialsSource.get(DB_CREDENTIALS_PARAMETER_PATH)).thenReturn(DB_CREDENTIALS);
    when(dbConnectionFactory.open(DB_CREDENTIALS)).thenReturn(dbConnection);
    when(dbConnection.isValid(anyInt())).thenReturn(true);
    return new MigrationRequest(command, SNAPSHOT_KEY, VERSION_ID, calculateSha256Hex(snapshotZipBytes));
  }

  /** The work directory is the parent of {@code root/migrations}, which is what Flyway is given. */
  private Path captureTempWorkDirPassedToFlyway(Command command) {
    ArgumentCaptor<Path> migrationsDirCaptor = ArgumentCaptor.forClass(Path.class);
    verify(flywayRunner).run(eq(command), migrationsDirCaptor.capture(), any(), anyString());
    return migrationsDirCaptor.getValue().getParent().getParent();
  }
}
