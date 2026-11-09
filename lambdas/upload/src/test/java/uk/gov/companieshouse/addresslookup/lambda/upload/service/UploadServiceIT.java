package uk.gov.companieshouse.addresslookup.lambda.upload.service;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.argThat;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;

import com.amazonaws.services.lambda.runtime.events.SQSEvent;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.fasterxml.jackson.databind.node.TextNode;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import javax.sql.DataSource;
import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.postgresql.PostgreSQLContainer;
import org.testcontainers.utility.DockerImageName;
import uk.gov.companieshouse.addresslookup.lambda.shared.acquisition.Commands;
import uk.gov.companieshouse.addresslookup.lambda.shared.storage.ReleaseStore;
import uk.gov.companieshouse.addresslookup.lambda.shared.testsupport.FakeS3;
import uk.gov.companieshouse.addresslookup.lambda.upload.runtime.UploadProperties;
import uk.gov.companieshouse.addresslookup.releasecore.aurora.AuroraOperations;
import uk.gov.companieshouse.release.model.Dataset;
import uk.gov.companieshouse.release.model.DatasetCatalog;

/**
 * Drives the SQS entry point end to end, as {@code upload_role}, against the real migrations:
 * scan-checked S3 objects in, landing rows, ledger and StepRejected out.
 */
@Testcontainers
class UploadServiceIT {
  private static final String UPLOAD_ROLE = "upload_role";
  private static final String UPLOAD_PASSWORD = "upload-secret";
  private static final String SCANNED = "scanned";
  private static final String INGEST = "ingest";
  private static final String TARGET = "2026-05-01";
  private static final Dataset GB = Dataset.ADD_GB_ROYALMAILADDRESS;
  private static final Dataset ISL = Dataset.ADD_ISL_ROYALMAILADDRESS;
  private static final ObjectMapper JSON = new ObjectMapper();

  @SuppressWarnings("resource")
  @Container
  private static final PostgreSQLContainer POSTGRES =
      new PostgreSQLContainer(
              DockerImageName.parse("postgis/postgis:18-3.6").asCompatibleSubstituteFor("postgres"))
          .withCreateContainerCmdModifier(command -> command.withPlatform("linux/amd64"));

  private static JdbcTemplate admin;
  private static StagingLoader loader;

  private FakeS3 s3;
  private Commands commands;
  private UploadService service;
  private String releaseId;

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
    DataSource upload = dataSource(UPLOAD_ROLE, UPLOAD_PASSWORD);
    var jdbc = new JdbcTemplate(upload);
    loader =
        new StagingLoader(
            new AuroraOperations(jdbc), jdbc, new DataSourceTransactionManager(upload));
  }

  @BeforeEach
  void setUp() {
    s3 = new FakeS3();
    commands = mock(Commands.class);
    service =
        new UploadService(s3.client(), new UploadProperties(SCANNED, INGEST), loader, commands);
    releaseId = UUID.randomUUID().toString();
  }

  @Test
  void islCsvLoadsWithLedgerRowCountEqualToCsvDataRowsAndRedeliveryAddsNothing() throws Exception {
    int dataRows = 3;
    putManifest(1, dataRows);
    putCsv(ISL, csv(ISL, dataRows));

    var manifestMessage = sqsMessage("m1", manifestEvent());
    var csvMessage = sqsMessage("c1", csvEvent(ISL));

    assertTrue(service.upload(batch(manifestMessage)).getBatchItemFailures().isEmpty());
    assertTrue(service.upload(batch(csvMessage)).getBatchItemFailures().isEmpty());
    assertTrue(service.upload(batch(csvMessage, manifestMessage)).getBatchItemFailures().isEmpty());

    assertEquals(dataRows, landed(ISL));
    assertEquals(List.of(3L), ledgerRowCounts(ISL));
    assertEquals(2, manifestRows());
    verify(commands, never()).sendIngestion(any(), any());
  }

  @Test
  void headerMismatchIsRejectedForQuarantineAndWritesNothing() throws Exception {
    putManifest(2, 1);
    putCsv(GB, "udprn,not_a_column\n1,x\n2,y\n");

    var response =
        service.upload(batch(sqsMessage("m1", manifestEvent()), sqsMessage("c1", csvEvent(GB))));

    assertTrue(response.getBatchItemFailures().isEmpty());
    assertEquals(0, landed(GB));
    assertEquals(List.of(), ledgerRowCounts(GB));
    verify(commands)
        .sendIngestion(
            eq("StepRejected"),
            argThat((Map<String, ?> p) ->
                PermanentFailureException.HEADER_MISMATCH.equals(p.get("code"))
                    && "upload".equals(p.get("step"))
                    && releaseId.equals(p.get("releaseId"))));
  }

  @Test
  void csvArrivingBeforeItsManifestIsRetriedThenLoads() throws Exception {
    putManifest(2, 1);
    putCsv(GB, csv(GB, 2));

    var early = service.upload(batch(sqsMessage("c1", csvEvent(GB))));
    assertEquals(1, early.getBatchItemFailures().size());
    assertEquals(0, landed(GB));

    service.upload(batch(sqsMessage("m1", manifestEvent())));
    assertTrue(
        service.upload(batch(sqsMessage("c1", csvEvent(GB)))).getBatchItemFailures().isEmpty());

    assertEquals(2, landed(GB));
    assertEquals(List.of(2L), ledgerRowCounts(GB));
  }

  private void putManifest(long gbRows, long islRows) throws Exception {
    ObjectNode root = JSON.createObjectNode();
    root.set("releaseId", TextNode.valueOf(releaseId));
    root.set("mode", TextNode.valueOf("FULL"));
    root.set("target", TextNode.valueOf(TARGET));
    root.set("dataPackageId", TextNode.valueOf("1"));
    root.set("versionId", TextNode.valueOf("2"));
    root.set(GB.datasetName(), datasetSummary(GB, gbRows));
    root.set(ISL.datasetName(), datasetSummary(ISL, islRows));
    s3.objects.put(manifestKey(), JSON.writeValueAsBytes(root));
    s3.scanStatus.put(manifestKey(), ReleaseStore.CLEAN);
  }

  private void putCsv(Dataset dataset, String content) {
    String key = ReleaseStore.extractedCsvKey(releaseId, dataset.datasetName());
    s3.objects.put(key, content.getBytes(StandardCharsets.UTF_8));
    s3.scanStatus.put(key, ReleaseStore.CLEAN);
  }

  private String manifestKey() {
    return "acquisitions/" + TARGET + "/" + releaseId + ".json";
  }

  private String manifestEvent() {
    return """
    {"detail-type":"ManifestReady","detail":{"schemaVersion":1,"releaseId":"%s",
    "object":{"bucket":"%s","key":"%s","versionId":"%s"}}}
    """
        .formatted(releaseId, INGEST, manifestKey(), FakeS3.DEFAULT_VERSION);
  }

  private String csvEvent(Dataset dataset) {
    return """
    {"detail-type":"DatasetReady","detail":{"schemaVersion":1,"releaseId":"%s","dataset":"%s",
    "object":{"bucket":"%s","key":"%s","versionId":"%s"}}}
    """
        .formatted(
            releaseId,
            dataset.datasetName(),
            SCANNED,
            ReleaseStore.extractedCsvKey(releaseId, dataset.datasetName()),
            FakeS3.DEFAULT_VERSION);
  }

  private int landed(Dataset dataset) {
    return admin.query(
        "SELECT count(*) FROM os_stage_full." + dataset.datasetName() + " WHERE release_id=?::uuid",
        rs -> {
          if (!rs.next()) {
            throw new IllegalStateException("Landing count query returned no result");
          }
          return rs.getInt(1);
        },
        releaseId);
  }

  private List<Long> ledgerRowCounts(Dataset dataset) {
    return admin.queryForList(
        "SELECT row_count FROM os_ingest.import_ledger WHERE release_id=?::uuid AND dataset=?",
        Long.class,
        releaseId,
        dataset.datasetName());
  }

  private int manifestRows() {
    return admin.queryForObject(
        "SELECT count(*) FROM os_ingest.data_package_manifest WHERE release_id=?::uuid",
        Integer.class,
        releaseId);
  }

  private static ObjectNode datasetSummary(Dataset dataset, long rows) {
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
    summary.set("schemaVersion", TextNode.valueOf(DatasetCatalog.table(dataset).version()));
    summary.putNull("validFromDate");
    summary.set("validToDate", TextNode.valueOf(TARGET));
    summary.put("recordCount", rows);
    file.set("summary", summary);
    return file;
  }

  private static String csv(Dataset dataset, int rows) {
    var table = DatasetCatalog.table(dataset);
    var text = new StringBuilder(table.header()).append('\n');
    for (int row = 1; row <= rows; row++) {
      var values = new ArrayList<String>();
      for (var column : table.columns()) values.add(value(dataset, column, row));
      text.append(String.join(",", values)).append('\n');
    }
    return text.toString();
  }

  private static String value(Dataset dataset, DatasetCatalog.Column column, int row) {
    if (column.name().equals("udprn")) return Integer.toString(row);
    if (column.type().equals("geometry")) {
      return "\"SRID=" + (dataset == ISL ? 4258 : 27700) + ";POINT(1 1)\"";
    }
    if (column.type().startsWith("numeric")) return "1";
    if (column.type().startsWith("timestamp")) return "2026-01-01 00:00:00";
    return "x";
  }

  private static SQSEvent batch(SQSEvent.SQSMessage... records) {
    var batch = new SQSEvent();
    batch.setRecords(List.of(records));
    return batch;
  }

  private static SQSEvent.SQSMessage sqsMessage(String id, String body) {
    var message = new SQSEvent.SQSMessage();
    message.setMessageId(id);
    message.setBody(body);
    return message;
  }

  private static DataSource dataSource(String user, String password) {
    return new DriverManagerDataSource(POSTGRES.getJdbcUrl(), user, password);
  }
}
