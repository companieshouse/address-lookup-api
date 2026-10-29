package uk.gov.companieshouse.addresslookup.dbmigrate;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.postgresql.PostgreSQLContainer;
import org.testcontainers.utility.DockerImageName;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.SQLException;
import java.util.HexFormat;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Runs the real {@code database/migrations} (zipped as the gather-snapshot job zips them) and
 * small fixtures against PostGIS, through the same service the Lambda handler uses.
 */
@Testcontainers(disabledWithoutDocker = true)
class MigrationServiceIT {

  @Container
  @SuppressWarnings("resource")
  private static final PostgreSQLContainer POSTGRES =
      new PostgreSQLContainer(
              DockerImageName.parse("postgis/postgis:18-3.6").asCompatibleSubstituteFor("postgres"))
          .withCreateContainerCmdModifier(command -> command.withPlatform("linux/amd64"));

  private static final AtomicInteger DATABASES = new AtomicInteger();
  private static final String KEY = "address-lookup-api/db-schema-snapshots/address-lookup-db-test.zip";
  private static final List<String> BASELINE =
      List.of(
          "V2026.001__create_postgis_and_schemas.sql",
          "V2026.002__create_dataset_contract.sql",
          "V2026.003__create_royalmailaddress_v2_tables.sql",
          "V2026.004__create_address_lookup_view.sql");

  private String database;

  @BeforeEach
  void freshDatabase() throws SQLException {
    database = "migrate_" + DATABASES.incrementAndGet();
    try (Connection admin = connect(POSTGRES.getDatabaseName());
        var statement = admin.createStatement()) {
      statement.execute("CREATE DATABASE " + database);
    }
  }

  // ---- The real address lookup migrations ------------------------------------------------

  @Test
  void infoListsTheBaselineOnAnEmptyDatabase() throws Exception {
    var result = run(realMigrations(), Command.INFO);

    assertEquals(BASELINE, result.get("pending"));
    assertNull(result.get("schemaVersion"));
    assertFalse(schemaExists("os_data"), "info must not change anything");
  }

  @Test
  void migrateAppliesTheBaselineThenIsANoOp() throws Exception {
    byte[] snapshot = realMigrations();

    var first = run(snapshot, Command.MIGRATE);
    assertEquals("SUCCESS", first.get("status"));
    assertEquals(BASELINE, first.get("applied"));
    assertEquals("2026.004", first.get("schemaVersion"));
    assertTrue(viewExists("address_lookup", "address_lookup"));
    assertEquals(0, count("SELECT count(*) FROM address_lookup.address_lookup"), "no seed data ships");
    assertEquals(4, count("SELECT count(*) FROM os_control.index_definition"));

    assertEquals(List.of(), run(snapshot, Command.MIGRATE).get("applied"));
    var validate = run(snapshot, Command.VALIDATE);
    assertEquals(true, validate.get("valid"));
    assertEquals(List.of(), validate.get("pending"));
    assertEquals("2026.004", validate.get("schemaVersion"));
  }

  @Test
  void resultIdentifiesTheExactSnapshot() throws Exception {
    byte[] snapshot = realMigrations();
    var result = run(snapshot, Command.INFO);

    @SuppressWarnings("unchecked")
    var described = (Map<String, Object>) result.get("snapshot");
    assertEquals(KEY, described.get("key"));
    assertEquals("v1", described.get("versionId"));
    assertEquals(sha256(snapshot), described.get("sha256"));
    assertEquals(BASELINE.size(), described.get("files"));
  }

  // ---- Fixtures: edits, naming, atomicity, locks, errors ---------------------------------

  @Test
  void editedMigrationFailsValidationAndNothingMoreIsApplied() throws Exception {
    run(fixture(Map.of("V1__first.sql", "CREATE TABLE first_table (id int);")), Command.MIGRATE);

    byte[] tampered =
        fixture(
            Map.of(
                "V1__first.sql", "CREATE TABLE first_table (id bigint);",
                "V2__second.sql", "CREATE TABLE second_table (id int);"));
    var e = assertThrows(MigrationException.class, () -> run(tampered, Command.MIGRATE));

    assertEquals(MigrationException.Code.VALIDATION_FAILED, e.code());
    assertFalse(tableExists("public", "second_table"));
    assertEquals(MigrationException.Code.VALIDATION_FAILED,
        assertThrows(MigrationException.class, () -> run(tampered, Command.VALIDATE)).code());
  }

  @Test
  void misnamedMigrationFailsInsteadOfBeingSkipped() throws Exception {
    byte[] snapshot =
        fixture(Map.of("V1__first.sql", "CREATE TABLE first_table (id int);", "V2_missing_separator.sql", "SELECT 1;"));

    var e = assertThrows(MigrationException.class, () -> run(snapshot, Command.MIGRATE));

    assertEquals(MigrationException.Code.VALIDATION_FAILED, e.code());
    assertFalse(tableExists("public", "first_table"));
  }

  @Test
  void failingMigrationRollsBackCompletely() throws Exception {
    byte[] snapshot =
        fixture(Map.of("V1__half.sql", "CREATE TABLE half_table (id int);\nSELECT * FROM does_not_exist;"));

    var e = assertThrows(MigrationException.class, () -> run(snapshot, Command.MIGRATE));

    assertEquals(MigrationException.Code.MIGRATION_FAILED, e.code());
    assertFalse(tableExists("public", "half_table"), "the first statement must be rolled back");
  }

  @Test
  void concurrentIndexMigrationRunsOnItsOwn() throws Exception {
    byte[] snapshot =
        fixture(
            Map.of(
                "V1__table.sql", "CREATE TABLE indexed_table (id int, postcode text);",
                "V2__index.sql", "CREATE INDEX CONCURRENTLY IF NOT EXISTS indexed_table_postcode_idx ON public.indexed_table (postcode);"));

    var result = run(snapshot, Command.MIGRATE);

    assertEquals(List.of("V1__table.sql", "V2__index.sql"), result.get("applied"));
    assertEquals(1, count("SELECT count(*) FROM pg_indexes WHERE indexname = 'indexed_table_postcode_idx'"));
  }

  @Test
  void migrationGivesUpWhenALockIsHeldPastTheTimeout() throws Exception {
    run(fixture(Map.of("V1__table.sql", "CREATE TABLE busy_table (id int);")), Command.MIGRATE);
    byte[] next =
        fixture(
            Map.of(
                "V1__table.sql", "CREATE TABLE busy_table (id int);",
                "V2__alter.sql", "ALTER TABLE public.busy_table ADD COLUMN extra int;"));

    try (Connection reader = connect(database)) {
      reader.setAutoCommit(false);
      reader.createStatement().execute("LOCK TABLE public.busy_table IN ACCESS SHARE MODE");
      long started = System.nanoTime();
      var e = assertThrows(MigrationException.class, () -> run(next, Command.MIGRATE, "1s", false));
      long millis = (System.nanoTime() - started) / 1_000_000;

      assertEquals(MigrationException.Code.MIGRATION_FAILED, e.code());
      assertTrue(e.getMessage().contains("lock timeout"), e.getMessage());
      assertTrue(millis < 10_000, "gave up after " + millis + " ms");
      reader.rollback();
    }
    assertEquals(List.of("V2__alter.sql"), run(next, Command.MIGRATE).get("applied"), "a re-run succeeds");
  }

  @Test
  void repairRunsOnlyWhenAllowed() throws Exception {
    byte[] snapshot = fixture(Map.of("V1__first.sql", "CREATE TABLE first_table (id int);"));
    run(snapshot, Command.MIGRATE);

    assertEquals(MigrationException.Code.REPAIR_DISABLED,
        assertThrows(MigrationException.class, () -> run(snapshot, Command.REPAIR)).code());
    var repaired = run(snapshot, Command.REPAIR, "10s", true);
    assertEquals("SUCCESS", repaired.get("status"));
    assertEquals("1", repaired.get("schemaVersion"));
  }

  @Test
  void checksumMismatchIsReportedBeforeTheDatabaseIsTouched() throws Exception {
    byte[] snapshot = realMigrations();
    var request = new MigrationRequest(Command.MIGRATE, KEY, "v1", "0".repeat(64));

    var e = assertThrows(MigrationException.class, () -> service(snapshot, "10s", false).handle(request));

    assertEquals(MigrationException.Code.BUNDLE_CHECKSUM_MISMATCH, e.code());
    assertFalse(schemaExists("os_data"));
  }

  @Test
  void unreachableDatabaseIsReported() throws Exception {
    byte[] snapshot = realMigrations();
    var service =
        new MigrationService(
            config("10s", false),
            (bucket, key, version) -> new ByteArrayInputStream(snapshot),
            name -> new DatabaseCredentials("jdbc:postgresql://127.0.0.1:1/none", "u", "p"),
            ConnectionFactory.jdbc(),
            new FlywayRunner());

    var e =
        assertThrows(
            MigrationException.class,
            () -> service.handle(new MigrationRequest(Command.INFO, KEY, "v1", sha256(snapshot))));

    assertEquals(MigrationException.Code.DATABASE_UNAVAILABLE, e.code());
  }

  // ---- Helpers ---------------------------------------------------------------------------

  private Map<String, Object> run(byte[] snapshot, Command command) throws Exception {
    return run(snapshot, command, "10s", false);
  }

  private Map<String, Object> run(byte[] snapshot, Command command, String lockTimeout, boolean allowRepair)
      throws Exception {
    return service(snapshot, lockTimeout, allowRepair)
        .handle(new MigrationRequest(command, KEY, "v1", sha256(snapshot)));
  }

  private MigrationService service(byte[] snapshot, String lockTimeout, boolean allowRepair) {
    return new MigrationService(
        config(lockTimeout, allowRepair),
        (bucket, key, version) -> {
          assertEquals("snapshot-bucket", bucket);
          assertEquals(KEY, key);
          return new ByteArrayInputStream(snapshot);
        },
        name -> {
          assertEquals("/address-lookup/test/db/schema-owner", name);
          return new DatabaseCredentials(jdbcUrl(database), POSTGRES.getUsername(), POSTGRES.getPassword());
        },
        ConnectionFactory.jdbc(),
        new FlywayRunner());
  }

  private static MigrationConfig config(String lockTimeout, boolean allowRepair) {
    return new MigrationConfig(
        "snapshot-bucket", "/address-lookup/test/db/schema-owner", allowRepair, lockTimeout, 50_000_000, 1000);
  }

  /** Mirrors the gather-snapshot job: database/migrations/** stored as migrations/<file>. */
  private static byte[] realMigrations() throws IOException {
    Path migrations =
        Path.of(System.getProperty("database.dir", "../../database")).resolve("migrations");
    var files = new TreeMap<String, String>();
    try (var paths = Files.list(migrations)) {
      for (Path file : paths.filter(Files::isRegularFile).toList())
        files.put(file.getFileName().toString(), Files.readString(file));
    }
    return fixture(files);
  }

  private static byte[] fixture(Map<String, String> migrations) throws IOException {
    var bytes = new ByteArrayOutputStream();
    try (var zip = new ZipOutputStream(bytes)) {
      for (var e : new TreeMap<>(migrations).entrySet()) {
        zip.putNextEntry(new ZipEntry("migrations/" + e.getKey()));
        zip.write(e.getValue().getBytes(StandardCharsets.UTF_8));
        zip.closeEntry();
      }
    }
    return bytes.toByteArray();
  }

  private static String sha256(byte[] bytes) throws Exception {
    return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(bytes));
  }

  private static String jdbcUrl(String db) {
    return "jdbc:postgresql://%s:%d/%s".formatted(POSTGRES.getHost(), POSTGRES.getMappedPort(5432), db);
  }

  private static Connection connect(String db) throws SQLException {
    return DriverManager.getConnection(jdbcUrl(db), POSTGRES.getUsername(), POSTGRES.getPassword());
  }

  private boolean exists(String sql, String... args) throws SQLException {
    try (var c = connect(database);
        var s = c.prepareStatement(sql)) {
      for (int i = 0; i < args.length; i++) s.setString(i + 1, args[i]);
      try (var rows = s.executeQuery()) {
        return rows.next();
      }
    }
  }

  private int count(String sql) throws SQLException {
    try (var c = connect(database);
        var s = c.createStatement();
        var rows = s.executeQuery(sql)) {
      rows.next();
      return rows.getInt(1);
    }
  }

  private boolean schemaExists(String schema) throws SQLException {
    return exists("SELECT 1 FROM information_schema.schemata WHERE schema_name = ?", schema);
  }

  private boolean tableExists(String schema, String table) throws SQLException {
    return exists(
        "SELECT 1 FROM information_schema.tables WHERE table_schema = ? AND table_name = ? AND table_type = 'BASE TABLE'",
        schema,
        table);
  }

  private boolean viewExists(String schema, String view) throws SQLException {
    return exists("SELECT 1 FROM information_schema.views WHERE table_schema = ? AND table_name = ?", schema, view);
  }
}
