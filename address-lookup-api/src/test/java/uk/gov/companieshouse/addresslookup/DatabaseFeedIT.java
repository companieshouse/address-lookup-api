package uk.gov.companieshouse.addresslookup;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.stream.Collectors;
import java.util.stream.Stream;
import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.postgresql.PostgreSQLContainer;
import org.testcontainers.utility.DockerImageName;
import org.testcontainers.utility.MountableFile;

/**
 * Verifies the root Flyway database feed and its local seed migrations.
 */
@Testcontainers
class DatabaseFeedIT {

  private static final List<String> TABLES =
      List.of("add_gb_royalmailaddress_v2", "add_isl_royalmailaddress_v2");
  private static final List<String> LANDING_DATASETS =
      List.of(
          "add_gb_royalmailaddress",
          "add_isl_royalmailaddress",
          "add_gb_builtaddress",
          "add_gb_prebuildaddress",
          "add_isl_builtaddress",
          "add_isl_prebuildaddress");
  private static final List<String> NGD_V3_DATASETS = LANDING_DATASETS.subList(2, 6);
  private static final List<String> OFFICIAL_COLUMNS =
      List.of(
          "udprn",
          "versiondate",
          "versionavailablefromdate",
          "versionavailabletodate",
          "changetype",
          "theme",
          "description",
          "organisationname",
          "departmentname",
          "subbuildingname",
          "buildingname",
          "buildingnumber",
          "dependentthoroughfare",
          "thoroughfare",
          "doubledependentlocality",
          "dependentlocality",
          "posttown",
          "postcode",
          "postcodetype",
          "postcodearea",
          "postcodedistrict",
          "postcodesector",
          "pifchecksumdigit",
          "pifdisplaytext",
          "fulladdress",
          "country",
          "deliverypointsuffix",
          "welshdependentthoroughfare",
          "welshthoroughfare",
          "welshdoubledependentlocality",
          "welshdependentlocality",
          "welshposttown",
          "poboxnumber",
          "updatedate",
          "entrydate",
          "easting",
          "northing",
          "latitude",
          "longitude",
          "geometry",
          "positionalaccuracy",
          "geometryallocationmethod",
          "unmatchedreason",
          "unmatchedreasondate",
          "uprn",
          "matchedaddressfeaturetype",
          "primaryuprnmatch",
          "matchtype",
          "matchdate",
          "matchmethod",
          "matchingorganisation");
  private static final Map<String, String> COLUMN_TYPES =
      Map.ofEntries(
          Map.entry("udprn", "integer"),
          Map.entry("versiondate", "date"),
          Map.entry("versionavailablefromdate", "timestamp without time zone"),
          Map.entry("versionavailabletodate", "timestamp without time zone"),
          Map.entry("changetype", "varchar(50)"),
          Map.entry("theme", "varchar(40)"),
          Map.entry("description", "varchar(25)"),
          Map.entry("organisationname", "varchar(60)"),
          Map.entry("departmentname", "varchar(60)"),
          Map.entry("subbuildingname", "varchar(30)"),
          Map.entry("buildingname", "varchar(50)"),
          Map.entry("buildingnumber", "integer"),
          Map.entry("dependentthoroughfare", "varchar(80)"),
          Map.entry("thoroughfare", "varchar(80)"),
          Map.entry("doubledependentlocality", "varchar(35)"),
          Map.entry("dependentlocality", "varchar(35)"),
          Map.entry("posttown", "varchar(30)"),
          Map.entry("postcode", "varchar(8)"),
          Map.entry("postcodetype", "varchar(10)"),
          Map.entry("postcodearea", "varchar(2)"),
          Map.entry("postcodedistrict", "varchar(4)"),
          Map.entry("postcodesector", "varchar(6)"),
          Map.entry("pifchecksumdigit", "varchar(1)"),
          Map.entry("pifdisplaytext", "varchar(50)"),
          Map.entry("fulladdress", "varchar(1000)"),
          Map.entry("country", "varchar(25)"),
          Map.entry("deliverypointsuffix", "varchar(2)"),
          Map.entry("welshdependentthoroughfare", "varchar(80)"),
          Map.entry("welshthoroughfare", "varchar(80)"),
          Map.entry("welshdoubledependentlocality", "varchar(35)"),
          Map.entry("welshdependentlocality", "varchar(35)"),
          Map.entry("welshposttown", "varchar(30)"),
          Map.entry("poboxnumber", "varchar(13)"),
          Map.entry("updatedate", "date"),
          Map.entry("entrydate", "date"),
          Map.entry("easting", "numeric(8,2)"),
          Map.entry("northing", "numeric(9,2)"),
          Map.entry("latitude", "numeric(9,7)"),
          Map.entry("longitude", "numeric(9,7)"),
          Map.entry("geometry", "geometry"),
          Map.entry("positionalaccuracy", "varchar(25)"),
          Map.entry("geometryallocationmethod", "varchar(25)"),
          Map.entry("unmatchedreason", "varchar(100)"),
          Map.entry("unmatchedreasondate", "date"),
          Map.entry("uprn", "bigint"),
          Map.entry("matchedaddressfeaturetype", "varchar(25)"),
          Map.entry("primaryuprnmatch", "varchar(5)"),
          Map.entry("matchtype", "varchar(20)"),
          Map.entry("matchdate", "date"),
          Map.entry("matchmethod", "varchar(10)"),
          Map.entry("matchingorganisation", "varchar(25)"),
          Map.entry("release_id", "text"));
  private static final Set<String> NULLABLE_COLUMNS =
      Set.of(
          "versionavailabletodate",
          "organisationname",
          "departmentname",
          "subbuildingname",
          "buildingname",
          "buildingnumber",
          "dependentthoroughfare",
          "thoroughfare",
          "doubledependentlocality",
          "dependentlocality",
          "pifchecksumdigit",
          "pifdisplaytext",
          "country",
          "welshdependentthoroughfare",
          "welshthoroughfare",
          "welshdoubledependentlocality",
          "welshdependentlocality",
          "welshposttown",
          "poboxnumber",
          "unmatchedreason",
          "unmatchedreasondate",
          "uprn",
          "matchedaddressfeaturetype",
          "primaryuprnmatch",
          "matchdate",
          "matchmethod",
          "matchingorganisation");
  private static final Set<String> REQUESTED_POSTCODES =
      Stream.concat(
              Stream.of(
                  "WF27QD", "AB101AU", "BT100BU", "BT100EQ", "BT513TU", "GY80EE", "GY79AD",
                  "IM11BD", "JE27ND", "JE27NA", "CF143AT", "CF146RP", "CF239XN", "CF243DJ",
                  "CF102BY", "CF245HB", "CF101FG", "CF102EF", "CF101GA", "CF102HQ", "CF101PX",
                  "CF102DP", "E139JU", "PO334PL", "IG95AZ", "WC1N1JP", "N16AR", "CF118TA",
                  "CF145XW", "KT138GL"),
              BorderPostcodeFixtures.compactPostcodes().stream())
          .collect(Collectors.toUnmodifiableSet());

  @SuppressWarnings("resource")
  @Container
  private static final PostgreSQLContainer POSTGRES =
      new PostgreSQLContainer(
              DockerImageName.parse("postgis/postgis:18-3.6").asCompatibleSubstituteFor("postgres"))
          .withCreateContainerCmdModifier(command -> command.withPlatform("linux/amd64"));

  /**
   * Checks fresh production migrations, local fixtures, and repeatable seed reruns.
   */
  @Test
  void migratesAndSeedsFreshPostgisDatabaseRepeatably(
      @TempDir Path tempSeeds, @TempDir Path tempCsvSeeds) throws Exception {
    Path databaseRoot = repositoryRoot().resolve("database");
    Flyway productionFlyway = flyway(databaseRoot.resolve("migrations"));

    assertProductionMigrationsAreRepeatable(productionFlyway);
    assertProductionSchema();

    System.setProperty(
        "als.local.seed.dir", Path.of("src/test/resources/seed-csv").toAbsolutePath().toString());
    Flyway localFlyway =
        flyway(
            databaseRoot.resolve("migrations"),
            databaseRoot.resolve("local"),
            Path.of(CSV_SEED_LOCATION));
    long seedCount;
    try (Stream<Path> files = Files.list(databaseRoot.resolve("local"))) {
      seedCount = files.filter(path -> path.getFileName().toString().endsWith(".sql")).count();
    }
    // The CSV seeds are Java repeatable migrations and add to the SQL seed count.
    assertEquals(seedCount + 2, localFlyway.migrate().migrationsExecuted);
    assertLocalFixtures();

    Map<String, Long> localRowCounts = new HashMap<>();
    for (String table : TABLES) {
      localRowCounts.put(table, rowCount(table, "local"));
      addNonLocalSentinel(table);
    }

    copySeedsWithChangedChecksums(databaseRoot.resolve("local"), tempSeeds);
    Flyway changedSeedFlyway =
        flyway(databaseRoot.resolve("migrations"), tempSeeds, Path.of(CSV_SEED_LOCATION));
    assertEquals(seedCount, changedSeedFlyway.migrate().migrationsExecuted);
    changedSeedFlyway.validate();

    assertCsvSeedsReload(changedSeedFlyway, tempCsvSeeds, localRowCounts);
    assertDuplicateUdprnsAreRemovedAtTheEnd(changedSeedFlyway, tempCsvSeeds);

    Files.writeString(
        tempSeeds.resolve("R__seed_additional_postcode.sql"),
        "SELECT 1;\n",
        StandardOpenOption.CREATE_NEW);
    Path expandedSeeds = Files.createDirectory(tempSeeds.resolve("expanded"));
    copySeedsWithChangedChecksums(tempSeeds, expandedSeeds);
    Flyway expandedSeedFlyway =
        flyway(databaseRoot.resolve("migrations"), expandedSeeds, Path.of(CSV_SEED_LOCATION));
    assertEquals(seedCount + 1, expandedSeedFlyway.migrate().migrationsExecuted);
    expandedSeedFlyway.validate();

    assertSeedsPreservedRows(localRowCounts);
    assertLocalFixtures();

    assertFullLoadProceduresImportIndexAndPromoteRepeatably(tempCsvSeeds);
  }

  /**
   * Runs the FULL import, index and promote procedures against a stand-in for aws_s3 that reads the
   * Landing files from the container, covering re-runs, a failed completeness check and the swap.
   */
  private static void assertFullLoadProceduresImportIndexAndPromoteRepeatably(Path landing)
      throws Exception {
    Path fixtures = Path.of("src/test/resources/seed-csv");
    long gbRows = Files.readAllLines(fixtures.resolve("add_gb_royalmailaddress.csv")).size() - 1;
    long islRows = Files.readAllLines(fixtures.resolve("add_isl_royalmailaddress.csv")).size() - 1;
    String directory = "/tmp/landing/full/p1/v1/";
    for (String dataset : LANDING_DATASETS) {
      POSTGRES.copyFileToContainer(
          MountableFile.forHostPath(fixtures.resolve(dataset + ".csv")),
          directory + dataset + ".csv");
    }
    String importCall = "CALL os_ingest.import_version('p1', 'v1', 'bucket', 'eu-west-2')";
    try (Connection connection = connection();
        Statement statement = connection.createStatement()) {
      // SECURITY DEFINER lets the non-superuser loader role read the container files.
      statement.execute(
          "CREATE SCHEMA aws_s3; "
              + "CREATE FUNCTION aws_s3.table_import_from_s3("
              + "t text, c text, o text, b text, k text, r text) RETURNS text LANGUAGE plpgsql "
              + "SECURITY DEFINER AS $$ "
              + "BEGIN EXECUTE format('COPY %s (%s) FROM %L WITH %s', t, c, '/tmp/landing/' || k, o); "
              + "RETURN 'ok'; END $$; "
              + "CREATE ROLE als_loader NOLOGIN; GRANT " + POSTGRES.getUsername() + " TO als_loader; "
              + "CREATE ROLE als_reader NOLOGIN; "
              + "GRANT USAGE ON SCHEMA address_lookup TO als_reader; "
              + "GRANT SELECT ON address_lookup.address_lookup TO als_reader; "
              + "GRANT SELECT ON os_data.add_gb_royalmailaddress_v2 TO als_reader "
              + "WITH GRANT OPTION");
      long activeGbBefore =
          scalar(statement, "SELECT count(*) FROM os_data.add_gb_royalmailaddress_v2");
      // the new datasets are registered disabled until Discovery and the release model support them
      assertEquals(
          4, scalar(statement, "SELECT count(*) FROM os_control.schema_contract WHERE NOT enabled"));
      statement.execute("UPDATE os_control.schema_contract SET enabled = true");
      statement.execute("SET ROLE als_loader");

      assertImportFails(landing, directory, statement, importCall, "rel-1", gbRows + 1, islRows,
          "27700", "2.0", "Completeness check failed");
      assertFalse(
          tableExists(statement, "os_stage.data_package_manifest WHERE release_id = 'rel-1'"));
      assertImportFails(landing, directory, statement, importCall, "rel-1", gbRows, islRows,
          "4258", "2.0", "crs");
      assertImportFails(landing, directory, statement, importCall, "rel-1", gbRows, islRows,
          "27700", "3.0", "schemaVersion");

      String gbCsv = Files.readString(fixtures.resolve("add_gb_royalmailaddress.csv"));
      Path badHeader = landing.resolve("bad-header.csv");
      Files.writeString(badHeader, gbCsv.replaceFirst("^[^,]+", "not_a_column"));
      POSTGRES.copyFileToContainer(
          MountableFile.forHostPath(badHeader), directory + "add_gb_royalmailaddress.csv");
      writeAndCopyManifest(landing, directory, "rel-1", gbRows, islRows, "27700", "2.0");
      assertTrue(
          assertThrows(SQLException.class, () -> statement.execute(importCall))
              .getMessage()
              .contains("header"));
      POSTGRES.copyFileToContainer(
          MountableFile.forHostPath(fixtures.resolve("add_gb_royalmailaddress.csv")),
          directory + "add_gb_royalmailaddress.csv");

      statement.execute(importCall);
      statement.execute(importCall);
      assertTrue(
          assertThrows(SQLException.class, () -> statement.execute("CALL os_ingest.promote('rel-1')"))
              .getMessage()
              .contains("Indexes are missing"));
      assertEquals(
          0,
          scalar(
              statement,
              "SELECT count(*) FROM information_schema.columns WHERE table_schema = 'os_stage_full' "
                  + "AND table_name LIKE '%_v2' AND column_name = 'release_id' AND column_default IS NOT NULL"));
      statement.execute("CALL os_ingest.build_indexes('rel-1')");
      statement.execute("CALL os_ingest.build_indexes('rel-1')");

      statement.execute("RESET ROLE");
      promoteWhileReading();
      statement.execute("SET ROLE als_loader");
      statement.execute("CALL os_ingest.promote('rel-1')");
      statement.execute("RESET ROLE");

      assertEquals(
          gbRows + islRows, scalar(statement, "SELECT count(*) FROM address_lookup.address_lookup"));
      assertEquals(
          gbRows, scalar(statement, "SELECT count(*) FROM os_data.add_gb_royalmailaddress_v2"));
      assertEquals(
          activeGbBefore, scalar(statement, "SELECT count(*) FROM os_prev.add_gb_royalmailaddress_v2"));
      assertEquals(
          0,
          scalar(
              statement,
              "SELECT count(*) FROM os_data.add_gb_royalmailaddress_v2 WHERE release_id <> 'rel-1'"));
      assertEquals(
          27700,
          scalar(statement, "SELECT Find_SRID('os_data', 'add_gb_royalmailaddress_v2', 'geometry')"));
      assertEquals(
          4258,
          scalar(statement, "SELECT Find_SRID('os_data', 'add_isl_royalmailaddress_v2', 'geometry')"));
      for (String dataset : NGD_V3_DATASETS) {
        assertEquals(1, scalar(statement, "SELECT count(*) FROM os_data." + dataset));
        assertEquals(
            dataset.startsWith("add_gb") ? 27700 : 4258,
            scalar(statement, "SELECT Find_SRID('os_data', '" + dataset + "', 'geometry')"));
      }
      assertEquals(1, scalar(statement, "SELECT count(*) FROM os_data.release_history"));
      assertEquals(
          1,
          scalar(
              statement,
              "SELECT count(*) FROM os_data.release_history "
                  + "WHERE release_id = 'rel-1' AND previous IS NULL AND mode = 'FULL'"));
      assertEquals(
          2, scalar(statement, "SELECT count(*) FROM pg_indexes WHERE schemaname = 'os_data' "
              + "AND tablename = 'add_gb_royalmailaddress_v2'"));

      // The loader role created the staging tables; they must end up owned like the active ones.
      assertEquals(
          0,
          scalar(
              statement,
              "SELECT count(*) FROM pg_tables WHERE schemaname = 'os_data' "
                  + "AND tablename LIKE '%_royalmailaddress_v2' AND tableowner <> current_user"));
      assertEquals(
          1,
          scalar(
              statement,
              "SELECT count(*) FROM information_schema.role_table_grants WHERE grantee = 'als_reader' "
                  + "AND table_schema = 'os_data' AND table_name = 'add_gb_royalmailaddress_v2' "
                  + "AND is_grantable = 'YES'"));
      statement.execute("SET ROLE als_reader");
      assertEquals(
          gbRows + islRows, scalar(statement, "SELECT count(*) FROM address_lookup.address_lookup"));
      statement.execute("RESET ROLE");

      // A second distinct FULL replaces the populated os_prev generation: os_prev must then hold
      // rel-1 (not the original tables) while the view and os_data serve rel-2.
      List<String> gbLines = Files.readAllLines(fixtures.resolve("add_gb_royalmailaddress.csv"));
      Path shorterGb = landing.resolve("rel-2-gb.csv");
      Files.write(shorterGb, gbLines.subList(0, gbLines.size() - 1));
      POSTGRES.copyFileToContainer(
          MountableFile.forHostPath(shorterGb), directory + "add_gb_royalmailaddress.csv");
      writeAndCopyManifest(landing, directory, "rel-2", gbRows - 1, islRows, "27700", "2.0",
          "2026-06-01");
      statement.execute("SET ROLE als_loader");
      statement.execute(importCall);
      statement.execute("CALL os_ingest.build_indexes('rel-2')");
      statement.execute("CALL os_ingest.promote('rel-2')");
      statement.execute("RESET ROLE");

      assertEquals(
          gbRows - 1,
          scalar(statement, "SELECT count(*) FROM os_data.add_gb_royalmailaddress_v2 "
              + "WHERE release_id = 'rel-2'"));
      assertEquals(
          gbRows - 1, scalar(statement, "SELECT count(*) FROM os_data.add_gb_royalmailaddress_v2"));
      assertEquals(
          gbRows,
          scalar(statement, "SELECT count(*) FROM os_prev.add_gb_royalmailaddress_v2 "
              + "WHERE release_id = 'rel-1'"));
      assertEquals(
          gbRows, scalar(statement, "SELECT count(*) FROM os_prev.add_gb_royalmailaddress_v2"));
      assertEquals(
          islRows,
          scalar(statement, "SELECT count(*) FROM os_prev.add_isl_royalmailaddress_v2 "
              + "WHERE release_id = 'rel-1'"));
      assertEquals(
          gbRows - 1 + islRows,
          scalar(statement, "SELECT count(*) FROM address_lookup.address_lookup"));
      assertEquals(2, scalar(statement, "SELECT count(*) FROM os_data.release_history"));
      assertEquals(
          0,
          scalar(statement, "SELECT count(*) FROM pg_tables WHERE schemaname = 'os_prev' "
              + "AND tablename NOT LIKE '%_royalmailaddress_v2' "
              + "AND tablename NOT IN ('add_gb_builtaddress', 'add_gb_prebuildaddress', "
              + "'add_isl_builtaddress', 'add_isl_prebuildaddress')"));

      statement.execute("SET ROLE als_loader");
      assertImportFails(landing, directory, statement, importCall, "rel-0", gbRows, islRows,
          "27700", "2.0", "older than the active data", "2026-04-01");
      statement.execute("RESET ROLE");
    }
  }

  // Reproduces the read/promote deadlock: a reader holds a table lock, promote starts and waits for
  // it, then a view read arrives. promote must lock the view first, so that read queues behind
  // promote instead of deadlocking with it once the first reader finishes.
  private static void promoteWhileReading() throws Exception {
    ExecutorService executor = Executors.newFixedThreadPool(2);
    try (Connection holder = connection();
        Connection promoter = connection();
        Connection reader = connection();
        Statement monitor = connection().createStatement()) {
      holder.setAutoCommit(false);
      holder.createStatement().executeQuery("SELECT count(*) FROM os_data.add_isl_royalmailaddress_v2");
      promoter.createStatement().execute("SET ROLE als_loader");

      Future<?> promotion =
          executor.submit(
              () -> {
                promoter.createStatement().execute("CALL os_ingest.promote('rel-1')");
                return null;
              });
      awaitLockWait(monitor, "CALL os_ingest.promote%");
      Future<Long> read =
          executor.submit(
              () -> scalar(reader.createStatement(), "SELECT count(*) FROM address_lookup.address_lookup"));
      awaitLockWait(monitor, "SELECT count(*) FROM address_lookup.address_lookup%");
      holder.commit();

      promotion.get(20, TimeUnit.SECONDS);
      assertTrue(read.get(20, TimeUnit.SECONDS) > 0);
    } finally {
      executor.shutdownNow();
    }
  }

  private static void awaitLockWait(Statement monitor, String queryPattern)
      throws SQLException {
    String sql =
        "SELECT count(*) FROM pg_stat_activity WHERE wait_event_type = 'Lock' "
            + "AND pid <> pg_backend_pid() AND query LIKE '" + queryPattern + "'";
    for (int attempt = 0; attempt < 100; attempt++) {
      if (scalar(monitor, sql) > 0) {
        return;
      }
      monitor.execute("SELECT pg_sleep(0.02)");
    }
    throw new AssertionError("No session waiting for a lock: " + queryPattern);
  }

  private static void assertImportFails(
      Path landing, String directory, Statement statement, String importCall, String releaseId,
      long gbRows, long islRows, String gbSrid, String schemaVersion, String expectedMessage)
      throws IOException {
    assertImportFails(landing, directory, statement, importCall, releaseId, gbRows, islRows, gbSrid,
        schemaVersion, expectedMessage, "2026-05-01");
  }

  private static void assertImportFails(
      Path landing, String directory, Statement statement, String importCall, String releaseId,
      long gbRows, long islRows, String gbSrid, String schemaVersion, String expectedMessage,
      String target) throws IOException {
    writeAndCopyManifest(
        landing, directory, releaseId, gbRows, islRows, gbSrid, schemaVersion, target);
    SQLException failure = assertThrows(SQLException.class, () -> statement.execute(importCall));
    assertTrue(failure.getMessage().contains(expectedMessage), failure::getMessage);
  }

  private static void writeAndCopyManifest(
      Path landing, String directory, String releaseId, long gbRows, long islRows, String gbSrid,
      String schemaVersion) throws IOException {
    writeAndCopyManifest(landing, directory, releaseId, gbRows, islRows, gbSrid, schemaVersion,
        "2026-05-01");
  }

  private static void writeAndCopyManifest(
      Path landing, String directory, String releaseId, long gbRows, long islRows, String gbSrid,
      String schemaVersion, String target) throws IOException {
    Files.writeString(
        landing.resolve("manifest.json"),
        "{\"releaseId\":\"" + releaseId + "\",\"mode\":\"FULL\",\"target\":\"" + target + "\","
            + "\"dataPackageId\":\"p1\",\"versionId\":\"v1\","
            + "\"add_gb_royalmailaddress\":{\"summary\":{\"recordCount\":" + gbRows
            + ",\"schemaVersion\":\"" + schemaVersion + "\","
            + "\"crs\":\"http://www.opengis.net/def/crs/EPSG/0/" + gbSrid + "\"}},"
            + "\"add_isl_royalmailaddress\":{\"summary\":{\"recordCount\":" + islRows
            + ",\"schemaVersion\":\"2.0\","
            + "\"crs\":\"http://www.opengis.net/def/crs/EPSG/0/4258\"}}"
            + ngdV3Manifest("add_gb_builtaddress", "27700")
            + ngdV3Manifest("add_gb_prebuildaddress", "27700")
            + ngdV3Manifest("add_isl_builtaddress", "4258")
            + ngdV3Manifest("add_isl_prebuildaddress", "4258") + "}");
    POSTGRES.copyFileToContainer(
        MountableFile.forHostPath(landing.resolve("manifest.json")), directory + "manifest.json");
  }

  private static String ngdV3Manifest(String dataset, String srid) {
    return ",\"" + dataset + "\":{\"summary\":{\"recordCount\":1,\"schemaVersion\":\"3.0\","
        + "\"crs\":\"http://www.opengis.net/def/crs/EPSG/0/" + srid + "\"}}";
  }

  private static boolean tableExists(Statement statement, String fromClause) throws SQLException {
    return scalar(statement, "SELECT count(*) FROM " + fromClause) > 0;
  }

  private static long scalar(Statement statement, String sql) throws SQLException {
    try (ResultSet result = statement.executeQuery(sql)) {
      result.next();
      return result.getLong(1);
    }
  }

  private static void assertProductionMigrationsAreRepeatable(Flyway productionFlyway) {
    assertEquals(17, productionFlyway.migrate().migrationsExecuted);
    productionFlyway.validate();
    assertEquals(0, productionFlyway.migrate().migrationsExecuted);
  }

  // A new .csv.version marker changes the Java seed checksum, so both CSV seeds must
  // reload.
  private static void assertCsvSeedsReload(
      Flyway flyway, Path tempCsvSeeds, Map<String, Long> localRowCounts)
      throws IOException, SQLException {
    Path fixtureCsvs = Path.of("src/test/resources/seed-csv");
    for (String table : TABLES) {
      String dataset = table.replace("_v2", "");
      Files.copy(fixtureCsvs.resolve(dataset + ".csv"), tempCsvSeeds.resolve(dataset + ".csv"));
      Files.writeString(tempCsvSeeds.resolve(dataset + ".csv.version"), "reload-test/1");
    }
    System.setProperty("als.local.seed.dir", tempCsvSeeds.toAbsolutePath().toString());
    assertEquals(2, flyway.migrate().migrationsExecuted);
    flyway.validate();
    for (String table : TABLES) {
      assertEquals(localRowCounts.get(table), rowCount(table, "local"));
      assertEquals(1, rowCount(table, "preserve-test"));
    }
  }

  private static void bumpSeedVersion(Path seedDirectory, String dataset, String version)
      throws IOException {
    Files.writeString(seedDirectory.resolve(dataset + ".csv.version"), version);
  }

  // Every CSV row is staged first; duplicate udprns are removed last, keeping the newest version.
  private static void assertDuplicateUdprnsAreRemovedAtTheEnd(Flyway flyway, Path tempCsvSeeds)
      throws IOException, SQLException {
    String gb = "add_gb_royalmailaddress";
    Path csv = tempCsvSeeds.resolve(gb + ".csv");
    List<String> lines = Files.readAllLines(csv);
    List<String> duplicated = new java.util.ArrayList<>(lines);
    duplicated.add(lines.get(1));
    Files.write(csv, duplicated);
    bumpSeedVersion(tempCsvSeeds, gb, "duplicate-1");
    assertEquals(1, flyway.migrate().migrationsExecuted);
    assertEquals(lines.size() - 1, rowCount(gb + "_v2", "local"));
    assertEquals(lines.size() - 1, countDistinctUdprns(gb + "_v2", "local"));
  }

  private static void assertSeedsPreservedRows(Map<String, Long> localRowCounts)
      throws SQLException {
    for (String table : TABLES) {
      assertEquals(localRowCounts.get(table), rowCount(table, "local"));
      assertEquals(1, rowCount(table, "preserve-test"));
      assertEquals(1, countDistinctUdprns(table, "preserve-test"));
    }
  }

  private static final String CSV_SEED_LOCATION = "db/local/java";

  @AfterEach
  void restoreFixtureSeedDirectory() {
    System.setProperty(
        "als.local.seed.dir", Path.of("src/test/resources/seed-csv").toAbsolutePath().toString());
  }

  private static Flyway flyway(Path... locations) {
    return Flyway.configure()
        .dataSource(POSTGRES.getJdbcUrl(), POSTGRES.getUsername(), POSTGRES.getPassword())
        .locations(
            List.of(locations).stream()
                .map(path ->
                    CSV_SEED_LOCATION.equals(path.toString())
                        ? "classpath:" + CSV_SEED_LOCATION
                        : "filesystem:" + path.toAbsolutePath())
                .toArray(String[]::new))
        .load();
  }

  private static Path repositoryRoot() {
    Path candidate = Path.of("").toAbsolutePath();
    while (candidate != null && !Files.isDirectory(candidate.resolve("database/migrations"))) {
      candidate = candidate.getParent();
    }
    assertNotNull(candidate, "Could not locate database/migrations from the test directory");
    return candidate;
  }

  private static void assertProductionSchema() throws SQLException {
    try (Connection connection = connection()) {
      assertRelation(connection, "os_control.schema_contract", true);
      assertRelation(connection, "os_control.index_definition", true);
      assertRelation(connection, "address_lookup.address_lookup", true);
      for (String table : TABLES) {
        assertRelation(connection, "os_data." + table, true);
        assertRelation(connection, "os_data." + table.replace("_v2", "_v1"), false);
        assertPhysicalColumns(connection, table);
        assertPrimaryKey(connection, table);
        assertPostcodeIndex(connection, table);
      }
      Set<String> activeTables = new HashSet<>(TABLES);
      activeTables.add("release_history");
      activeTables.addAll(NGD_V3_DATASETS);
      assertEquals(activeTables, physicalTableNames(connection));
      assertAls143Objects(connection);

      Map<String, String> contracts = new HashMap<>();
      try (Statement statement = connection.createStatement();
          ResultSet rows =
              statement.executeQuery("SELECT dataset, version FROM os_control.schema_contract")) {
        while (rows.next()) {
          contracts.put(rows.getString("dataset"), rows.getString("version"));
        }
      }
      assertEquals(
          Map.of(
              "add_gb_royalmailaddress", "2.0",
              "add_isl_royalmailaddress", "2.0",
              "add_gb_builtaddress", "3.0",
              "add_gb_prebuildaddress", "3.0",
              "add_isl_builtaddress", "3.0",
              "add_isl_prebuildaddress", "3.0"),
          contracts);
      assertIndexMetadata(connection);

      assertEquals(27700, geometrySrid(connection, TABLES.get(0)));
      assertEquals(4258, geometrySrid(connection, TABLES.get(1)));
      try (Statement statement = connection.createStatement()) {
        statement.executeQuery(
            "SELECT id, address_source, udprn, postcode, latitude, longitude, country "
                + "FROM address_lookup.address_lookup LIMIT 0");
      }
      assertNullableColumn(connection, TABLES.get(0), "country", true);
      assertNullableColumn(connection, TABLES.get(0), "easting", false);
      assertNullableColumn(connection, TABLES.get(0), "northing", false);
      assertNullableColumn(connection, TABLES.get(1), "country", true);
      assertNullableColumn(connection, TABLES.get(1), "easting", true);
      assertNullableColumn(connection, TABLES.get(1), "northing", true);
      assertNullableColumn(connection, TABLES.get(0), "release_id", false);
      assertNullableColumn(connection, TABLES.get(1), "release_id", false);
      assertVarcharLength(connection, TABLES.get(0), "description", 25);
      assertVarcharLength(connection, TABLES.get(1), "description", 25);
    }
  }

  private static void assertAls143Objects(Connection connection) throws SQLException {
    assertRelation(connection, "os_stage.data_package_manifest", true);
    assertRelation(connection, "os_stage.import_ledger", true);
    assertRelation(connection, "os_stage.add_gb_royalmailaddress", true);
    assertRelation(connection, "os_stage.add_isl_royalmailaddress", true);
    assertRelation(connection, "os_data.release_history", true);
    for (String dataset : NGD_V3_DATASETS) {
      assertRelation(connection, "os_data." + dataset, true);
      assertRelation(connection, "os_stage." + dataset, true);
      assertRelation(connection, "os_data." + dataset + "_altadd", false);
      assertStagingColumnsMatchCsvHeader(connection, dataset);
    }
    try (Statement statement = connection.createStatement()) {
      assertEquals(
          4,
          scalar(
              statement,
              "SELECT count(*) FROM os_control.schema_contract WHERE version = '3.0' "
                  + "AND active_table = dataset"));
      assertEquals(
          6, scalar(statement, "SELECT count(DISTINCT dataset) FROM os_control.index_definition"));
      assertEquals(
          4,
          scalar(
              statement,
              "SELECT count(*) FROM os_control.index_definition WHERE index_type = 'primary_key' "
                  + "AND definition = 'uprn'"));
    }
    try (Statement statement = connection.createStatement()) {
      statement.execute(
          "INSERT INTO os_stage.data_package_manifest "
              + "(release_id, mode, target, package_id, version_id, datasets) VALUES "
              + "('r1', 'FULL', 't', 'p', 'v', "
              + "'[{\"dataset\":\"add_gb_royalmailaddress\",\"file\":\"a.csv\",\"rows\":5}]')");
      assertFalse(completeness(statement, "r1"));
      statement.execute(
          "INSERT INTO os_stage.import_ledger (release_id, dataset, file, rows) "
              + "VALUES ('r1', 'add_gb_royalmailaddress', 'a.csv', 4)");
      assertFalse(completeness(statement, "r1"));
      statement.execute(
          "UPDATE os_stage.import_ledger SET rows = 5 WHERE release_id = 'r1'");
      assertTrue(completeness(statement, "r1"));
      assertFalse(completeness(statement, "unknown"));
      statement.execute("DELETE FROM os_stage.import_ledger");
      statement.execute("DELETE FROM os_stage.data_package_manifest");
    }
  }

  private static void assertStagingColumnsMatchCsvHeader(Connection connection, String dataset)
      throws SQLException {
    try {
      List<String> header =
          List.of(
              Files.readAllLines(Path.of("src/test/resources/seed-csv/" + dataset + ".csv"))
                  .get(0)
                  .split(","));
      List<String> columns = new ArrayList<>();
      try (Statement statement = connection.createStatement();
          ResultSet result =
              statement.executeQuery(
                  "SELECT column_name FROM information_schema.columns "
                      + "WHERE table_schema = 'os_stage' AND table_name = '" + dataset
                      + "' AND column_name <> 'release_id' ORDER BY ordinal_position")) {
        while (result.next()) {
          columns.add(result.getString(1));
        }
      }
      assertEquals(header, columns);

      String file = "/tmp/staging-check/" + dataset + ".csv";
      POSTGRES.copyFileToContainer(
          MountableFile.forHostPath(Path.of("src/test/resources/seed-csv/" + dataset + ".csv")),
          file);
      String columnList =
          header.stream().map(c -> "\"" + c + "\"").collect(Collectors.joining(","));
      try (Statement statement = connection.createStatement()) {
        statement.execute(
            "COPY os_stage." + dataset + " (" + columnList + ") FROM '" + file
                + "' WITH (FORMAT csv, HEADER true)");
        assertEquals(1, scalar(statement, "SELECT count(*) FROM os_stage." + dataset));
        statement.execute("DELETE FROM os_stage." + dataset);
      }
    } catch (IOException e) {
      throw new IllegalStateException(e);
    }
  }

  private static boolean completeness(Statement statement, String releaseId) throws SQLException {
    try (ResultSet result =
        statement.executeQuery("SELECT os_ingest.is_release_complete('" + releaseId + "')")) {
      result.next();
      return result.getBoolean(1);
    }
  }

  private static void assertPhysicalColumns(Connection connection, String table)
      throws SQLException {
    List<String> columns = new ArrayList<>();
    Map<String, String> types = new HashMap<>();
    Map<String, Boolean> nullable = new HashMap<>();
    try (PreparedStatement statement =
        connection.prepareStatement(
            "SELECT column_name, data_type, udt_name, character_maximum_length, "
                + "numeric_precision, numeric_scale, is_nullable "
                + "FROM information_schema.columns "
                + "WHERE table_schema = 'os_data' AND table_name = ? "
                + "ORDER BY ordinal_position")) {
      statement.setString(1, table);
      try (ResultSet result = statement.executeQuery()) {
        while (result.next()) {
          String column = result.getString("column_name");
          columns.add(column);
          types.put(column, columnType(result));
          nullable.put(column, result.getString("is_nullable").equals("YES"));
        }
      }
    }
    List<String> expectedColumns = new ArrayList<>(OFFICIAL_COLUMNS);
    expectedColumns.add("release_id");
    assertEquals(expectedColumns, columns, "OS v2 column order differs for " + table);
    assertEquals(COLUMN_TYPES, types, "OS v2 column types differ for " + table);

    Set<String> expectedNullableColumns = new HashSet<>(NULLABLE_COLUMNS);
    if (table.equals(TABLES.get(1))) {
      expectedNullableColumns.add("easting");
      expectedNullableColumns.add("northing");
    }
    Map<String, Boolean> expectedNullability = new HashMap<>();
    COLUMN_TYPES
        .keySet()
        .forEach(
            column -> expectedNullability.put(column, expectedNullableColumns.contains(column)));
    assertEquals(expectedNullability, nullable, "OS v2 nullability differs for " + table);
  }

  private static String columnType(ResultSet result) throws SQLException {
    return switch (result.getString("data_type")) {
      case "character varying" -> "varchar(" + result.getInt("character_maximum_length") + ")";
      case "numeric" ->
          "numeric("
              + result.getInt("numeric_precision")
              + ","
              + result.getInt("numeric_scale")
              + ")";
      case "USER-DEFINED" -> result.getString("udt_name");
      default -> result.getString("data_type");
    };
  }

  private static Set<String> physicalTableNames(Connection connection) throws SQLException {
    Set<String> names = new HashSet<>();
    try (Statement statement = connection.createStatement();
        ResultSet result =
            statement.executeQuery(
                "SELECT table_name FROM information_schema.tables "
                    + "WHERE table_schema = 'os_data' AND table_type = 'BASE TABLE'")) {
      while (result.next()) {
        names.add(result.getString(1));
      }
    }
    return names;
  }

  private static void assertPostcodeIndex(Connection connection, String table) throws SQLException {
    String indexName = table + "_postcode_idx";
    try (PreparedStatement statement =
        connection.prepareStatement(
            "SELECT pg_get_expr(indexes.indexprs, indexes.indrelid) AS expression, "
                + "indexes.indisvalid, indexes.indisunique, "
                + "(indexes.indpred IS NULL) AS is_unfiltered "
                + "FROM pg_index indexes "
                + "JOIN pg_class index_relation ON index_relation.oid = indexes.indexrelid "
                + "JOIN pg_namespace index_schema ON index_schema.oid = index_relation.relnamespace "
                + "WHERE index_schema.nspname = 'os_data' AND index_relation.relname = ?")) {
      statement.setString(1, indexName);
      try (ResultSet result = statement.executeQuery()) {
        assertTrue(result.next(), "Missing postcode index " + indexName);
        String expression =
            result
                .getString("expression")
                .toLowerCase(Locale.ROOT)
                .replace("::text", "")
                .replaceAll("\\s*,\\s*", ",")
                .replaceAll("\\(\\s+", "(")
                .replaceAll("\\s+\\)", ")")
                .replace("(postcode)", "postcode");
        assertEquals("upper(replace(postcode,' ',''))", expression);
        assertTrue(result.getBoolean("indisvalid"), "Invalid postcode index " + indexName);
        assertFalse(result.getBoolean("indisunique"));
        assertTrue(result.getBoolean("is_unfiltered"));
        assertFalse(result.next(), "Duplicate index metadata for " + indexName);
      }
    }
  }

  private static void assertPrimaryKey(Connection connection, String table) throws SQLException {
    try (PreparedStatement statement =
        connection.prepareStatement(
            "SELECT constraints.conname, pg_get_constraintdef(constraints.oid) AS definition "
                + "FROM pg_constraint constraints "
                + "JOIN pg_class tables ON tables.oid = constraints.conrelid "
                + "JOIN pg_namespace schemas ON schemas.oid = tables.relnamespace "
                + "WHERE schemas.nspname = 'os_data' AND tables.relname = ? "
                + "AND constraints.contype = 'p'")) {
      statement.setString(1, table);
      try (ResultSet result = statement.executeQuery()) {
        assertTrue(result.next(), "Missing primary key for " + table);
        assertEquals(table + "_pkey", result.getString("conname"));
        assertEquals("PRIMARY KEY (udprn)", result.getString("definition"));
        assertFalse(result.next(), "Duplicate primary keys for " + table);
      }
    }
  }

  private static void assertIndexMetadata(Connection connection) throws SQLException {
    Set<String> expected =
        new HashSet<>(Set.of(
            "add_gb_royalmailaddress|add_gb_royalmailaddress_v2|"
                + "add_gb_royalmailaddress_v2_pkey|primary_key|udprn",
            "add_gb_royalmailaddress|add_gb_royalmailaddress_v2|"
                + "add_gb_royalmailaddress_v2_postcode_idx|index|"
                + "upper(replace(postcode, ' ', ''))",
            "add_isl_royalmailaddress|add_isl_royalmailaddress_v2|"
                + "add_isl_royalmailaddress_v2_pkey|primary_key|udprn",
            "add_isl_royalmailaddress|add_isl_royalmailaddress_v2|"
                + "add_isl_royalmailaddress_v2_postcode_idx|index|"
                + "upper(replace(postcode, ' ', ''))"));
    for (String dataset : NGD_V3_DATASETS) {
      expected.add(dataset + "|" + dataset + "|" + dataset + "_pkey|primary_key|uprn");
      expected.add(
          dataset + "|" + dataset + "|" + dataset + "_postcode_idx|index|"
              + "upper(replace(postcode, ' ', ''))");
    }
    Set<String> actual = new HashSet<>();
    try (Statement statement = connection.createStatement();
        ResultSet result =
            statement.executeQuery(
                "SELECT dataset, table_name, index_name, index_type, definition "
                    + "FROM os_control.index_definition")) {
      while (result.next()) {
        actual.add(
            String.join(
                "|",
                result.getString("dataset"),
                result.getString("table_name").replace("os_data.", ""),
                result.getString("index_name"),
                result.getString("index_type"),
                result.getString("definition")));
      }
    }
    assertEquals(expected, actual);
  }

  private static void assertLocalFixtures() throws SQLException {
    assertEquals(694, rowCount(TABLES.get(0), "local"));
    assertEquals(273, rowCount(TABLES.get(1), "local"));
    assertEquals(30, scenarioPostcodeRowCount());
    assertEquals("d1122a3868c28205a5716d5c2b99a02b", contentHash(TABLES.get(0)));
    assertEquals("7f9f3d38d5c7a3ae5e4637efce7e8456", contentHash(TABLES.get(1)));
    try (Connection connection = connection()) {
      for (String postcode : REQUESTED_POSTCODES) {
        try (PreparedStatement statement =
            connection.prepareStatement(
                "SELECT count(*) FROM address_lookup.address_lookup "
                    + "WHERE upper(replace(postcode, ' ', '')) = ?")) {
          statement.setString(1, postcode);
          try (ResultSet result = statement.executeQuery()) {
            assertTrue(result.next());
            if ("CF101PX".equals(postcode)) {
              assertEquals(0, result.getLong(1), "No source records for " + postcode);
            } else {
              assertTrue(result.getLong(1) > 0, "Missing postcode fixture " + postcode);
            }
          }
        }
      }
      assertTrue(countSource(connection, "GB") > 0, "No GB view rows");
      assertTrue(countSource(connection, "ISL") > 0, "No ISL view rows");
      assertViewSourceForPostcode(connection, "WF27QD", "GB");
      assertViewSourceForPostcode(connection, "CF143AT", "GB");
      assertViewSourceForPostcode(connection, "KT138GL", "GB");
      assertViewSourceForPostcode(connection, "GY79AD", "ISL");
      assertEquals(0, countInvalidSourceIds(connection));
      assertRegionGeometry(connection, TABLES.get(0), 27700, true);
      assertRegionGeometry(connection, TABLES.get(1), 4258, false);
      assertCountryFixture(connection, TABLES.get(0), 19, "Scotland");
      assertCountryFixture(connection, TABLES.get(1), 10403188, "Channel Islands");
    }
  }

  private static long scenarioPostcodeRowCount() throws SQLException {
    List<String> postcodes = List.of("E139JU", "PO334PL", "IG95AZ", "WC1N1JP", "N16AR");
    try (Connection connection = connection();
        PreparedStatement statement =
            connection.prepareStatement(
                "SELECT count(*) FROM os_data.add_gb_royalmailaddress_v2 "
                    + "WHERE release_id = 'local' "
                    + "AND upper(replace(postcode, ' ', '')) IN (?, ?, ?, ?, ?)")) {
      for (int index = 0; index < postcodes.size(); index++) {
        statement.setString(index + 1, postcodes.get(index));
      }
      try (ResultSet result = statement.executeQuery()) {
        assertTrue(result.next());
        return result.getLong(1);
      }
    }
  }

  // Hash of the rows loaded by the retired SQL seeds, so the CSV load is proven identical.
  private static String contentHash(String table) throws SQLException {
    try (Connection connection = connection();
        Statement statement = connection.createStatement();
        ResultSet result =
            statement.executeQuery(
                "SELECT md5(string_agg(t::text, E'\\n' ORDER BY udprn)) FROM os_data."
                    + table
                    + " t WHERE release_id = 'local'")) {
      assertTrue(result.next());
      return result.getString(1);
    }
  }

  private static void assertCountryFixture(
      Connection connection, String table, int udprn, String country) throws SQLException {
    try (PreparedStatement statement =
        connection.prepareStatement(
            "SELECT country, release_id FROM os_data." + table + " WHERE udprn = ?")) {
      statement.setInt(1, udprn);
      try (ResultSet result = statement.executeQuery()) {
        assertTrue(result.next(), "Missing country fixture " + udprn);
        assertEquals(country, result.getString("country"), "Country for fixture " + udprn);
        assertEquals("local", result.getString("release_id"));
        assertFalse(result.next(), "Duplicate fixture " + udprn);
      }
    }
  }

  private static void assertNullableColumn(
      Connection connection, String table, String column, boolean nullable) throws SQLException {
    try (PreparedStatement statement =
        connection.prepareStatement(
            "SELECT is_nullable FROM information_schema.columns "
                + "WHERE table_schema = 'os_data' AND table_name = ? AND column_name = ?")) {
      statement.setString(1, table);
      statement.setString(2, column);
      try (ResultSet result = statement.executeQuery()) {
        assertTrue(result.next(), "Missing column " + table + "." + column);
        assertEquals(nullable ? "YES" : "NO", result.getString(1));
        assertFalse(result.next(), "Duplicate column metadata for " + table + "." + column);
      }
    }
  }

  private static void assertVarcharLength(
      Connection connection, String table, String column, int length) throws SQLException {
    try (PreparedStatement statement =
        connection.prepareStatement(
            "SELECT data_type, character_maximum_length FROM information_schema.columns "
                + "WHERE table_schema = 'os_data' AND table_name = ? AND column_name = ?")) {
      statement.setString(1, table);
      statement.setString(2, column);
      try (ResultSet result = statement.executeQuery()) {
        assertTrue(result.next(), "Missing column " + table + "." + column);
        assertEquals("character varying", result.getString("data_type"));
        assertEquals(length, result.getInt("character_maximum_length"));
        assertFalse(result.next(), "Duplicate column metadata for " + table + "." + column);
      }
    }
  }

  private static long countSource(Connection connection, String source) throws SQLException {
    try (PreparedStatement statement =
        connection.prepareStatement(
            "SELECT count(*) FROM address_lookup.address_lookup WHERE address_source = ?")) {
      statement.setString(1, source);
      try (ResultSet result = statement.executeQuery()) {
        assertTrue(result.next());
        return result.getLong(1);
      }
    }
  }

  private static void assertViewSourceForPostcode(
      Connection connection, String postcode, String source) throws SQLException {
    try (PreparedStatement statement =
        connection.prepareStatement(
            "SELECT count(*) FROM address_lookup.address_lookup "
                + "WHERE upper(replace(postcode, ' ', '')) = ? AND address_source = ? "
                + "AND id LIKE ?")) {
      statement.setString(1, postcode);
      statement.setString(2, source);
      statement.setString(3, source + "-%");
      try (ResultSet result = statement.executeQuery()) {
        assertTrue(result.next());
        assertTrue(
            result.getLong(1) > 0, "No correctly labelled " + source + " rows for " + postcode);
      }
    }
  }

  private static long countInvalidSourceIds(Connection connection) throws SQLException {
    try (Statement statement = connection.createStatement();
        ResultSet result =
            statement.executeQuery(
                "SELECT count(*) FROM address_lookup.address_lookup "
                    + "WHERE id <> address_source || '-' || udprn::text")) {
      assertTrue(result.next());
      return result.getLong(1);
    }
  }

  private static void assertRegionGeometry(
      Connection connection, String table, int srid, boolean gb) throws SQLException {
    String invalidGeometry =
        gb
            ? "ST_SRID(geometry) <> ? OR easting IS NULL OR northing IS NULL "
                + "OR abs(ST_X(geometry) - easting) > 0.01 "
                + "OR abs(ST_Y(geometry) - northing) > 0.01"
            : "ST_SRID(geometry) <> ? "
                + "OR abs(ST_X(geometry) - longitude) > 0.0000001 "
                + "OR abs(ST_Y(geometry) - latitude) > 0.0000001";
    try (PreparedStatement statement =
        connection.prepareStatement(
            "SELECT count(*) FROM os_data."
                + table
                + " WHERE release_id = 'local' AND ("
                + invalidGeometry
                + ")")) {
      statement.setInt(1, srid);
      try (ResultSet result = statement.executeQuery()) {
        assertTrue(result.next());
        assertEquals(0, result.getLong(1), "Invalid local geometry in " + table);
      }
    }
  }

  private static void assertRelation(Connection connection, String relation, boolean exists)
      throws SQLException {
    try (PreparedStatement statement = connection.prepareStatement("SELECT to_regclass(?)")) {
      statement.setString(1, relation);
      try (ResultSet result = statement.executeQuery()) {
        assertTrue(result.next());
        if (exists) {
          assertNotNull(result.getString(1), "Missing relation " + relation);
        } else {
          assertNull(result.getString(1), "Unexpected relation " + relation);
        }
      }
    }
  }

  private static int geometrySrid(Connection connection, String table) throws SQLException {
    try (PreparedStatement statement =
        connection.prepareStatement(
            "SELECT srid FROM geometry_columns "
                + "WHERE f_table_schema = 'os_data' AND f_table_name = ? "
                + "AND f_geometry_column = 'geometry'")) {
      statement.setString(1, table);
      try (ResultSet result = statement.executeQuery()) {
        assertTrue(result.next(), "Missing geometry column for " + table);
        int srid = result.getInt("srid");
        assertFalse(result.next(), "Duplicate geometry metadata for " + table);
        return srid;
      }
    }
  }

  private static long rowCount(String table, String releaseId) throws SQLException {
    return count(table, releaseId, false);
  }

  private static long countDistinctUdprns(String table, String releaseId) throws SQLException {
    return count(table, releaseId, true);
  }

  private static long count(String table, String releaseId, boolean distinct) throws SQLException {
    String expression = distinct ? "count(DISTINCT udprn)" : "count(*)";
    try (Connection connection = connection();
        PreparedStatement statement =
            connection.prepareStatement(
                "SELECT " + expression + " FROM os_data." + table + " WHERE release_id = ?")) {
      statement.setString(1, releaseId);
      try (ResultSet result = statement.executeQuery()) {
        assertTrue(result.next());
        return result.getLong(1);
      }
    }
  }

  private static void addNonLocalSentinel(String table) throws SQLException {
    try (Connection connection = connection();
        Statement statement = connection.createStatement();
        ResultSet columns =
            statement.executeQuery(
                "SELECT column_name FROM information_schema.columns "
                    + "WHERE table_schema = 'os_data' AND table_name = '"
                    + table
                    + "' "
                    + "ORDER BY ordinal_position")) {
      List<String> names = new ArrayList<>();
      while (columns.next()) {
        names.add(columns.getString(1));
      }
      assertTrue(names.contains("udprn"));
      assertTrue(names.contains("release_id"));

      String columnList =
          names.stream()
              .map(DatabaseFeedIT::quoteIdentifier)
              .reduce((left, right) -> left + ", " + right)
              .orElseThrow();
      String selectList =
          names.stream()
              .map(name ->
                  switch (name) {
                    case "udprn" -> "\"udprn\" + 1000000000";
                    case "release_id" -> "'preserve-test'";
                    default -> quoteIdentifier(name);
                  })
              .reduce((left, right) -> left + ", " + right)
              .orElseThrow();
      try (Statement insert = connection.createStatement()) {
        assertEquals(
            1,
            insert.executeUpdate(
                "INSERT INTO os_data."
                    + table
                    + " ("
                    + columnList
                    + ") "
                    + "SELECT "
                    + selectList
                    + " FROM os_data."
                    + table
                    + " WHERE release_id = 'local' LIMIT 1"));
      }
    }
  }

  private static String quoteIdentifier(String identifier) {
    return "\"" + identifier.replace("\"", "\"\"") + "\"";
  }

  private static void copySeedsWithChangedChecksums(Path localSeeds, Path target)
      throws IOException {
    try (var files = Files.list(localSeeds)) {
      List<Path> seeds =
          files.filter(path -> path.getFileName().toString().endsWith(".sql")).toList();
      assertFalse(
          seeds.contains(localSeeds.resolve("R__seed_add_gb_royalmailaddress.sql")),
          "The GB SQL seed is replaced by the CSV migration");
      assertFalse(
          seeds.contains(localSeeds.resolve("R__seed_add_isl_royalmailaddress.sql")),
          "The ISL SQL seed is replaced by the CSV migration");
      assertFalse(
          seeds.contains(localSeeds.resolve("R__seed_synthetic_cardiff_addresses.sql")),
          "The retired Cardiff seed must not be present");
      for (Path seed : seeds) {
        Path copy = target.resolve(seed.getFileName());
        Files.copy(seed, copy);
        Files.writeString(
            copy, "\n-- force repeatable seed execution\n", StandardOpenOption.APPEND);
      }
    }
  }

  private static Connection connection() throws SQLException {
    return DriverManager.getConnection(
        POSTGRES.getJdbcUrl(), POSTGRES.getUsername(), POSTGRES.getPassword());
  }
}
