package uk.gov.companieshouse.addresslookup.lambda.upload.service;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.fasterxml.jackson.databind.node.TextNode;
import java.io.ByteArrayInputStream;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.Callable;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import javax.sql.DataSource;
import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.aop.framework.ProxyFactory;
import org.springframework.dao.DataAccessException;
import org.springframework.dao.support.PersistenceExceptionTranslationInterceptor;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.springframework.orm.jpa.vendor.HibernateJpaDialect;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.postgresql.PostgreSQLContainer;
import org.testcontainers.utility.DockerImageName;
import uk.gov.companieshouse.addresslookup.releasecore.aurora.AuroraOperations;
import uk.gov.companieshouse.release.model.Dataset;
import uk.gov.companieshouse.release.model.DatasetCatalog;
import uk.gov.companieshouse.release.model.ReleaseManifest;

/** Runs the real migrations on PostGIS and loads through the same transaction code as the Lambda. */
@Testcontainers
class StagingLoaderIT {
  private static final String UPLOAD_ROLE = "upload_role";
  private static final String UPLOAD_PASSWORD = "upload-secret";
  private static final Dataset GB = Dataset.ADD_GB_ROYALMAILADDRESS;
  private static final Dataset ISL = Dataset.ADD_ISL_ROYALMAILADDRESS;
  private static final ObjectMapper JSON = new ObjectMapper();
  private static final String TIMESTAMP = "2026-01-01 12:34:56";

  @SuppressWarnings("resource")
  @Container
  private static final PostgreSQLContainer POSTGRES =
      new PostgreSQLContainer(
              DockerImageName.parse("postgis/postgis:18-3.6").asCompatibleSubstituteFor("postgres"))
          .withCreateContainerCmdModifier(command -> command.withPlatform("linux/amd64"));

  private static JdbcTemplate admin;
  private static TestLoader loader;
  private static TestLoader uploadRoleLoader;

  @BeforeAll
  static void migrate() {
    admin = new JdbcTemplate(dataSource(POSTGRES.getUsername(), POSTGRES.getPassword()));
    admin.execute("CREATE ROLE " + UPLOAD_ROLE + " LOGIN PASSWORD '" + UPLOAD_PASSWORD + "'");
    Flyway.configure()
        .dataSource(POSTGRES.getJdbcUrl(), POSTGRES.getUsername(), POSTGRES.getPassword())
        .locations(
            "filesystem:" + Path.of("../../database/migrations").toAbsolutePath().normalize())
        .defaultSchema("public")
        .load()
        .migrate();
    loader = loaderFor(dataSource(POSTGRES.getUsername(), POSTGRES.getPassword()));
    uploadRoleLoader = loaderFor(dataSource(UPLOAD_ROLE, UPLOAD_PASSWORD));
  }

  @BeforeEach
  void clean() {
    for (String table :
        List.of(
            "os_ingest.import_ledger",
            "os_ingest.data_package_manifest",
            "os_stage_full.add_gb_royalmailaddress",
            "os_stage_full.add_isl_royalmailaddress",
            "os_stage_raw.add_gb_royalmailaddress",
            "os_stage_raw.add_isl_royalmailaddress")) {
      admin.update("DELETE FROM " + table);
    }
  }

  @Test
  void fullLoadWritesLandingRowsLedgerAndManifestForEveryDataset() throws Exception {
    var manifest = manifest(UUID.randomUUID(), "FULL", 2, 1);

    assertTrue(loader.load(GB, manifest, "k@v1", csv(GB, 2)));

    String id = manifest.id().toString();
    assertEquals(2, count("os_stage_full.add_gb_royalmailaddress", id));
    assertEquals(
        2L,
        admin.queryForObject(
            "SELECT sum(row_count) FROM os_ingest.import_ledger WHERE release_id=?::uuid AND dataset=?",
            Long.class,
            id,
            GB.datasetName()));
    assertEquals(
        List.of("add_gb_royalmailaddress:2", "add_isl_royalmailaddress:1"),
        admin.queryForList(
            "SELECT dataset || ':' || record_count FROM os_ingest.data_package_manifest"
                + " WHERE release_id=?::uuid ORDER BY dataset",
            String.class,
            id));
    var row =
        admin.queryForMap(
            "SELECT supply_type, previous_release, target_release::text AS target, schema_version"
                + " FROM os_ingest.data_package_manifest WHERE release_id=?::uuid AND dataset=?",
            id,
            GB.datasetName());
    assertEquals("FULL", row.get("supply_type"));
    assertEquals(null, row.get("previous_release"));
    assertEquals("2026-05-01", row.get("target"));
    assertEquals("2.0", row.get("schema_version"));
  }

  @Test
  void landingKeepsTheTimeOfEveryCatalogueTimestamp() {
    var full = manifest(UUID.randomUUID(), "FULL", 1, 1);
    var cou = manifest(UUID.randomUUID(), "COU", 1, 1);

    assertTrue(loader.load(GB, full, "k@v1", csv(GB, 1)));
    assertTrue(loader.load(ISL, cou, "k@v1", csv(ISL, 1)));

    assertTimesKept("os_stage_full", GB, full);
    assertTimesKept("os_stage_raw", ISL, cou);
  }

  @Test
  void couLoadsIntoRawLandingTable() throws Exception {
    var manifest = manifest(UUID.randomUUID(), "COU", 1, 1);

    assertTrue(loader.load(ISL, manifest, "k@v1", csv(ISL, 1)));

    String id = manifest.id().toString();
    assertEquals(1, count("os_stage_raw.add_isl_royalmailaddress", id));
    assertEquals(0, count("os_stage_full.add_isl_royalmailaddress", id));
    assertEquals(
        "2026-04-01",
        admin.queryForObject(
            "SELECT previous_release::text FROM os_ingest.data_package_manifest"
                + " WHERE release_id=?::uuid AND dataset=?",
            String.class,
            id,
            ISL.datasetName()));
  }

  @Test
  void replayAndReExtractedVersionAreNoOpsAndNeverDoubleCount() throws Exception {
    var manifest = manifest(UUID.randomUUID(), "FULL", 2, 1);
    assertTrue(loader.load(GB, manifest, "k@v1", csv(GB, 2)));

    assertFalse(loader.load(GB, manifest, "k@v1", csv(GB, 2)));
    assertFalse(loader.load(GB, manifest, "k@v2", csv(GB, 2)));

    String id = manifest.id().toString();
    assertEquals(2, count("os_stage_full.add_gb_royalmailaddress", id));
    assertEquals(1, ledgerRows(id, GB));
    assertEquals(2, manifestRows(id));
  }

  @Test
  void ledgerRejectsSecondFileForSameDatasetEvenOutsideTheLoader() {
    String id = UUID.randomUUID().toString();
    String insert =
        "INSERT INTO os_ingest.import_ledger (release_id, dataset, file, row_count)"
            + " VALUES (?::uuid,?,?,1) ON CONFLICT DO NOTHING";

    assertEquals(1, admin.update(insert, id, GB.datasetName(), "a@v1"));
    assertEquals(0, admin.update(insert, id, GB.datasetName(), "a@v2"));
  }

  @Test
  void malformedRowRollsBackLandingLedgerAndManifest() {
    var manifest = manifest(UUID.randomUUID(), "FULL", 2, 1);
    String broken = header(GB) + "\nnot-a-number,x\n";
    var brokenCsv = stream(broken);

    var e =
        assertThrows(
            PermanentFailureException.class, () -> loader.load(GB, manifest, "k@v1", brokenCsv));

    assertEquals(PermanentFailureException.INVALID_CSV_DATA, e.code());

    assertNothingPersisted(manifest.id().toString());
  }

  @Test
  void recordCountMismatchRollsBackEverything() {
    var manifest = manifest(UUID.randomUUID(), "FULL", 3, 1);
    var csv = csv(GB, 2);

    var e =
        assertThrows(PermanentFailureException.class, () -> loader.load(GB, manifest, "k@v1", csv));

    assertEquals(PermanentFailureException.ROW_COUNT_MISMATCH, e.code());

    assertNothingPersisted(manifest.id().toString());
  }

  @Test
  void concurrentDuplicateDeliveriesLoadExactlyOnce() throws Exception {
    var manifest = manifest(UUID.randomUUID(), "FULL", 2, 1);
    var pool = Executors.newFixedThreadPool(4);
    try {
      List<Future<Boolean>> results = new ArrayList<>();
      for (int i = 0; i < 4; i++) {
        String file = "k@v" + i;
        Callable<Boolean> task = () -> loader.load(GB, manifest, file, csv(GB, 2));
        results.add(pool.submit(task));
      }
      int loaded = 0;
      for (var result : results) if (result.get()) loaded++;

      assertEquals(1, loaded);
      assertEquals(2, count("os_stage_full.add_gb_royalmailaddress", manifest.id().toString()));
      assertEquals(1, ledgerRows(manifest.id().toString(), GB));
    } finally {
      pool.shutdownNow();
    }
  }

  @Test
  void uploadRoleCanLoadButNothingElse() throws Exception {
    var manifest = manifest(UUID.randomUUID(), "FULL", 2, 1);
    assertTrue(uploadRoleLoader.load(GB, manifest, "k@v1", csv(GB, 2)));
    assertEquals(2, count("os_stage_full.add_gb_royalmailaddress", manifest.id().toString()));

    var upload = new JdbcTemplate(dataSource(UPLOAD_ROLE, UPLOAD_PASSWORD));
    for (String denied :
        List.of(
            "SELECT 1 FROM os_data.add_gb_royalmailaddress_v2",
            "SELECT 1 FROM os_control.schema_contract",
            "DELETE FROM os_ingest.import_ledger",
            "UPDATE os_ingest.import_ledger SET row_count = 0",
            "DELETE FROM os_ingest.data_package_manifest")) {
      assertThrows(DataAccessException.class, () -> upload.execute(denied), denied);
    }
  }

  @Test
  void catalogSchemaVersionsMatchTheMigratedContract() {
    for (var table : DatasetCatalog.tables()) {
      assertEquals(
          table.version(),
          admin.query(
              "SELECT version FROM os_control.schema_contract WHERE dataset=?",
              rs -> {
                if (!rs.next()) {
                  throw new IllegalStateException("Schema contract row is missing");
                }
                return rs.getString(1);
              },
              table.name()));
    }
  }

  @Test
  void islLedgerRowCountEqualsTheCsvDataRows() throws Exception {
    var manifest = manifest(UUID.randomUUID(), "FULL", 1, 3);

    assertTrue(loader.load(ISL, manifest, "k@v1", csv(ISL, 3)));

    String id = manifest.id().toString();
    assertEquals(3, count("os_stage_full.add_isl_royalmailaddress", id));
    assertEquals(
        3L,
        admin.queryForObject(
            "SELECT row_count FROM os_ingest.import_ledger WHERE release_id=?::uuid AND dataset=?",
            Long.class,
            id,
            ISL.datasetName()));
  }

  @Test
  void aDifferentManifestForTheSameReleaseIsAPermanentConflict() {
    var id = UUID.randomUUID();
    var stagingLoader = loader.delegate();
    stagingLoader.recordManifest(manifest(id, "FULL", 2, 1));
    stagingLoader.recordManifest(manifest(id, "FULL", 2, 1));
    var conflictingManifest = manifest(id, "FULL", 9, 1);

    var e =
        assertThrows(
            PermanentFailureException.class,
            () -> stagingLoader.recordManifest(conflictingManifest));

    assertEquals(PermanentFailureException.MANIFEST_CONFLICT, e.code());
    assertEquals(2, manifestRows(id.toString()));
  }

  @Test
  void manifestRecordedIsVisibleToTheUploadRoleOnlyAfterRecording() {
    var id = UUID.randomUUID();
    assertFalse(uploadRoleLoader.delegate().manifestRecorded(id));

    uploadRoleLoader.delegate().recordManifest(manifest(id, "FULL", 2, 1));

    assertTrue(uploadRoleLoader.delegate().manifestRecorded(id));
    assertFalse(uploadRoleLoader.delegate().manifestRecorded(UUID.randomUUID()));
  }

  @Test
  void rowCountMismatchIsPermanent() {
    var manifest = manifest(UUID.randomUUID(), "FULL", 3, 1);
    var csv = csv(GB, 2);

    var e =
        assertThrows(PermanentFailureException.class, () -> loader.load(GB, manifest, "k@v1", csv));

    assertEquals(PermanentFailureException.ROW_COUNT_MISMATCH, e.code());
  }

  @Test
  void uploadRoleHasNoWriteOrRoutineAccessOutsideItsLandingTables() {
    for (String schema : List.of("os_data", "os_prev", "os_control")) {
      assertFalse(
          admin.queryForObject(
              "SELECT has_schema_privilege(?, ?, 'USAGE')", Boolean.class, UPLOAD_ROLE, schema),
          schema);
    }
    assertEquals(
        0,
        admin.queryForObject(
            "SELECT count(*) FROM information_schema.role_table_grants WHERE grantee=?"
                + " AND table_schema IN ('os_data','os_prev','os_control')",
            Integer.class,
            UPLOAD_ROLE));

    admin.execute(
        "CREATE OR REPLACE FUNCTION os_ingest.it_probe() RETURNS int LANGUAGE sql AS 'SELECT 1'");
    try {
      var upload = new JdbcTemplate(dataSource(UPLOAD_ROLE, UPLOAD_PASSWORD));
      assertThrows(
          DataAccessException.class,
          () -> upload.queryForObject("SELECT os_ingest.it_probe()", Integer.class));
    } finally {
      admin.execute("DROP FUNCTION os_ingest.it_probe()");
    }
  }

  private void assertNothingPersisted(String id) {
    assertEquals(0, count("os_stage_full.add_gb_royalmailaddress", id));
    assertEquals(0, ledgerRows(id, GB));
  }

  private static void assertTimesKept(String schema, Dataset dataset, ReleaseManifest manifest) {
    for (var column : DatasetCatalog.table(dataset).columns()) {
      if (!column.type().startsWith("timestamp")) continue;
      assertEquals(
          TIMESTAMP,
          JdbcClient.create(admin)
              .sql(
                  "SELECT "
                      + column.name()
                      + "::text FROM "
                      + schema
                      + "."
                      + dataset.datasetName()
                      + " WHERE release_id=?::uuid")
              .param(manifest.id().toString())
              .query(String.class)
              .single(),
          schema + "." + dataset.datasetName() + "." + column.name());
    }
  }

  private static int count(String table, String releaseId) {
    return admin.queryForObject(
        "SELECT count(*) FROM " + table + " WHERE release_id=?::uuid", Integer.class, releaseId);
  }

  private static int ledgerRows(String releaseId, Dataset dataset) {
    return admin.queryForObject(
        "SELECT count(*) FROM os_ingest.import_ledger WHERE release_id=?::uuid AND dataset=?",
        Integer.class,
        releaseId,
        dataset.datasetName());
  }

  private static int manifestRows(String releaseId) {
    return admin.queryForObject(
        "SELECT count(*) FROM os_ingest.data_package_manifest WHERE release_id=?::uuid",
        Integer.class,
        releaseId);
  }

  private static DataSource dataSource(String user, String password) {
    return new DriverManagerDataSource(POSTGRES.getJdbcUrl(), user, password);
  }

  private static TestLoader loaderFor(DataSource dataSource) {
    var jdbc = new JdbcTemplate(dataSource);
    return new TestLoader(
        new StagingLoader(
            translated(new AuroraOperations(jdbc)),
            jdbc,
            new DataSourceTransactionManager(dataSource)));
  }

  /** Mirrors production, where AuroraOperations is a {@code @Repository} behind exception translation. */
  private static AuroraOperations translated(AuroraOperations target) {
    var proxy = new ProxyFactory(target);
    proxy.setProxyTargetClass(true);
    proxy.addAdvice(new PersistenceExceptionTranslationInterceptor(new HibernateJpaDialect()));
    return (AuroraOperations) proxy.getProxy();
  }

  /** Follows the Lambda flow: the manifest is recorded first, then the CSV is loaded against it. */
  private record TestLoader(StagingLoader delegate) {
    boolean load(Dataset dataset, ReleaseManifest manifest, String file, InputStream csv) {
      delegate.recordManifest(manifest);
      var staged = delegate.stagedManifest(manifest.id(), dataset).orElseThrow();
      return delegate.load(dataset, staged, file, csv);
    }
  }

  private static String header(Dataset dataset) {
    return DatasetCatalog.table(dataset).header();
  }

  private static InputStream stream(String text) {
    return new ByteArrayInputStream(text.getBytes(StandardCharsets.UTF_8));
  }

  private static InputStream csv(Dataset dataset, int rows) {
    var table = DatasetCatalog.table(dataset);
    var text = new StringBuilder(table.header()).append('\n');
    for (int row = 1; row <= rows; row++) {
      var values = new ArrayList<String>();
      for (var column : table.columns()) {
        values.add(value(dataset, column, row));
      }
      text.append(String.join(",", values)).append('\n');
    }
    return stream(text.toString());
  }

  private static String value(Dataset dataset, DatasetCatalog.Column column, int row) {
    if (column.name().equals("udprn")) return Integer.toString(row);
    if (column.type().equals("geometry")) {
      return "\"SRID=" + (dataset == ISL ? 4258 : 27700) + ";POINT(1 1)\"";
    }
    if (column.type().startsWith("numeric")) return "1";
    if (column.type().startsWith("timestamp")) return TIMESTAMP;
    return "x";
  }

  private static ReleaseManifest manifest(UUID id, String mode, long gbRows, long islRows) {
    try {
      ObjectNode root = JSON.createObjectNode();
      root.set("releaseId", TextNode.valueOf(id.toString()));
      root.set("mode", TextNode.valueOf(mode));
      if (mode.equals("COU")) root.set("previous", TextNode.valueOf("2026-04-01"));
      root.set("target", TextNode.valueOf("2026-05-01"));
      root.set("dataPackageId", TextNode.valueOf("1"));
      root.set("versionId", TextNode.valueOf("2"));
      root.set("add_gb_royalmailaddress", dataset(GB, mode, gbRows));
      root.set("add_isl_royalmailaddress", dataset(ISL, mode, islRows));
      return ReleaseManifest.read(root);
    } catch (Exception e) {
      throw new IllegalStateException(e);
    }
  }

  private static ObjectNode dataset(Dataset dataset, String mode, long rows) {
    ObjectNode file = JSON.createObjectNode();
    ObjectNode zip = JSON.createObjectNode();
    zip.set("fileName", TextNode.valueOf("a.zip"));
    zip.set("url", TextNode.valueOf("https://x"));
    zip.set("md5", TextNode.valueOf("229c68b69090208861bc3fdf05009ef6"));
    file.set("zip", zip);
    var summary = JSON.createObjectNode();
    summary.set("featureName", TextNode.valueOf(dataset.datasetName().replace('_', '-')));
    summary.set(
        "crs",
        TextNode.valueOf(
            "http://www.opengis.net/def/crs/EPSG/0/" + (dataset == ISL ? 4258 : 27700)));
    summary.set("schemaVersion", TextNode.valueOf("2.0"));
    if (mode.equals("COU")) summary.set("validFromDate", TextNode.valueOf("2026-04-01"));
    else summary.putNull("validFromDate");
    summary.set("validToDate", TextNode.valueOf("2026-05-01"));
    summary.put("recordCount", rows);
    file.set("summary", summary);
    return file;
  }
}
