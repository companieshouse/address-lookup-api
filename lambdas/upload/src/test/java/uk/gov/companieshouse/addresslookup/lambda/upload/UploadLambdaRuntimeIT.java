package uk.gov.companieshouse.addresslookup.lambda.upload;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.fasterxml.jackson.databind.node.TextNode;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.UUID;
import java.util.regex.Pattern;
import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.MethodOrderer;
import org.junit.jupiter.api.Order;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestMethodOrder;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.containers.Network;
import org.testcontainers.containers.wait.strategy.Wait;
import org.testcontainers.lifecycle.Startables;
import org.testcontainers.postgresql.PostgreSQLContainer;
import org.testcontainers.utility.DockerImageName;
import org.testcontainers.utility.MountableFile;
import software.amazon.awssdk.auth.credentials.AwsBasicCredentials;
import software.amazon.awssdk.auth.credentials.StaticCredentialsProvider;
import software.amazon.awssdk.core.sync.RequestBody;
import software.amazon.awssdk.regions.Region;
import software.amazon.awssdk.services.s3.S3Client;
import software.amazon.awssdk.services.s3.model.BucketVersioningStatus;
import software.amazon.awssdk.services.s3.model.CreateBucketConfiguration;
import software.amazon.awssdk.services.s3.model.PutObjectRequest;
import software.amazon.awssdk.services.s3.model.Tag;
import software.amazon.awssdk.services.s3.model.Tagging;
import uk.gov.companieshouse.addresslookup.lambda.shared.storage.ReleaseStore;
import uk.gov.companieshouse.addresslookup.lambda.upload.service.PermanentFailureException;
import uk.gov.companieshouse.release.model.Dataset;
import uk.gov.companieshouse.release.model.DatasetCatalog;

/**
 * Runs the shaded jar exactly as deployed: in the AWS Lambda Java runtime image (runtime interface
 * emulator), configured only through environment variables, connecting to TLS-only Postgres with an
 * IAM-style token, and talking to real S3 and EventBridge APIs (LocalStack). Invocations are real
 * HTTP invokes with SQS-shaped payloads, so this covers what the in-process ITs cannot: packaging,
 * handler wiring, Spring start-up, the IAM token login path, S3 versioning and tagging, and
 * StepRejected delivery.
 *
 * <p>Requires Docker and the packaged jar, so it runs in the {@code integration-test} phase.
 */
@TestMethodOrder(MethodOrderer.OrderAnnotation.class)
class UploadLambdaRuntimeIT {
  private static final ObjectMapper JSON = new ObjectMapper();
  private static final String REGION = "eu-west-2";
  private static final String SCANNED = "als-scanned";
  private static final String INGEST = "als-ingest";
  private static final String BUS = "ingest-bus";
  private static final String QUEUE = "step-rejected";
  private static final String S3_HOST = "s3.localhost.localstack.cloud";
  private static final String UPLOAD_ROLE = "upload_role";
  private static final String DATABASE = "address_lookup";
  private static final String TARGET = "2026-05-01";
  private static final Dataset GB = Dataset.ADD_GB_ROYALMAILADDRESS;
  private static final Dataset ISL = Dataset.ADD_ISL_ROYALMAILADDRESS;
  private static final Path JAR = Path.of("target/address-lookup-lambda-upload-latest.jar");
  private static final String HANDLER =
      "uk.gov.companieshouse.addresslookup.lambda.upload.Handler::handleRequest";
  private static final String TLS_POSTGRES =
      "openssl req -new -x509 -nodes -days 1 -subj /CN=db -keyout /tmp/server.key "
          + "-out /tmp/server.crt && chown postgres /tmp/server.key && chmod 600 /tmp/server.key "
          + "&& exec docker-entrypoint.sh postgres -c ssl=on -c ssl_cert_file=/tmp/server.crt "
          + "-c ssl_key_file=/tmp/server.key";

  private static final Network NETWORK = Network.newNetwork();

  @SuppressWarnings("resource")
  private static final PostgreSQLContainer POSTGRES =
      new PostgreSQLContainer(
              DockerImageName.parse("postgis/postgis:18-3.6").asCompatibleSubstituteFor("postgres"))
          .withDatabaseName(DATABASE)
          .withNetwork(NETWORK)
          .withNetworkAliases("db")
          .withCreateContainerCmdModifier(command ->
              command.withPlatform("linux/amd64").withEntrypoint("bash", "-c", TLS_POSTGRES));

  @SuppressWarnings("resource")
  private static final GenericContainer<?> LOCALSTACK =
      new GenericContainer<>(DockerImageName.parse("localstack/localstack:4"))
          .withNetwork(NETWORK)
          .withNetworkAliases(
              "localstack", S3_HOST, SCANNED + "." + S3_HOST, INGEST + "." + S3_HOST)
          .withEnv("SERVICES", "s3,events,sqs")
          .withExposedPorts(4566)
          .waitingFor(Wait.forHttp("/_localstack/health").forPort(4566));

  @SuppressWarnings("resource")
  private static final GenericContainer<?> LAMBDA =
      new GenericContainer<>(DockerImageName.parse("public.ecr.aws/lambda/java:21"))
          .withNetwork(NETWORK)
          .withCreateContainerCmdModifier(
              command -> command.getHostConfig().withMemory(1024L * 1024 * 1024))
          .withCopyFileToContainer(
              MountableFile.forHostPath(JAR.toAbsolutePath()), "/var/task/lib/upload.jar")
          .withCommand(HANDLER)
          .withEnv("AWS_REGION", REGION)
          .withEnv("AWS_ACCESS_KEY_ID", "test")
          .withEnv("AWS_SECRET_ACCESS_KEY", "test")
          .withEnv("AWS_LAMBDA_FUNCTION_MEMORY_SIZE", "1024")
          .withEnv("AWS_ENDPOINT_URL_S3", "http://" + S3_HOST + ":4566")
          .withEnv("AWS_ENDPOINT_URL_EVENTBRIDGE", "http://localstack:4566")
          .withEnv("SCANNED_BUCKET", SCANNED)
          .withEnv("INGEST_BUCKET", INGEST)
          .withEnv("EVENT_BUS", BUS)
          .withEnv("DB_HOST", "db")
          .withEnv("DB_PORT", "5432")
          .withEnv("DB_NAME", DATABASE)
          .withEnv("DB_USER", UPLOAD_ROLE)
          .withExposedPorts(8080)
          .waitingFor(Wait.forListeningPort());

  private static JdbcTemplate admin;
  private static S3Client s3;
  private static String queueUrl;
  private static final List<JsonNode> REJECTIONS = new ArrayList<>();
  private static final HttpClient HTTP = HttpClient.newHttpClient();

  private record Invocation(List<String> failedIds, boolean functionError, String raw) {}

  @BeforeAll
  static void start() throws Exception {
    assertTrue(Files.exists(JAR), "Run 'package' first, the shaded jar is missing: " + JAR);
    Startables.deepStart(POSTGRES, LOCALSTACK, LAMBDA).join();
    prepareDatabase();
    prepareAws();
  }

  @AfterAll
  static void stop() throws Exception {
    Files.writeString(Path.of("target/upload-lambda-runtime-it.log"), LAMBDA.getLogs());
    LAMBDA.stop();
    LOCALSTACK.stop();
    POSTGRES.stop();
    NETWORK.close();
  }

  private static void prepareDatabase() throws Exception {
    admin =
        new JdbcTemplate(
            new DriverManagerDataSource(
                POSTGRES.getJdbcUrl(), POSTGRES.getUsername(), POSTGRES.getPassword()));
    admin.execute("CREATE ROLE " + UPLOAD_ROLE + " LOGIN");
    Flyway.configure()
        .dataSource(POSTGRES.getJdbcUrl(), POSTGRES.getUsername(), POSTGRES.getPassword())
        .locations(
            "filesystem:" + Path.of("../../database/migrations").toAbsolutePath().normalize())
        .defaultSchema("public")
        .load()
        .migrate();
    // The IAM token is accepted as the password for TLS connections only, as RDS Proxy would.
    var hba =
        POSTGRES.execInContainer(
            "bash",
            "-c",
            "sed -i '1i hostssl all " + UPLOAD_ROLE + " all trust' \"$PGDATA/pg_hba.conf\"");
    assertEquals(0, hba.getExitCode(), hba.getStderr());
    admin.execute("SELECT pg_reload_conf()");
  }

  private static void prepareAws() throws Exception {
    s3 =
        S3Client.builder()
            .region(Region.of(REGION))
            .endpointOverride(URI.create("http://localhost:" + LOCALSTACK.getMappedPort(4566)))
            .forcePathStyle(true)
            .credentialsProvider(
                StaticCredentialsProvider.create(AwsBasicCredentials.create("test", "test")))
            .build();
    for (String bucket : List.of(SCANNED, INGEST)) {
      s3.createBucket(b ->
          b.bucket(bucket)
              .createBucketConfiguration(
                  CreateBucketConfiguration.builder().locationConstraint(REGION).build()));
      s3.putBucketVersioning(b ->
          b.bucket(bucket).versioningConfiguration(v -> v.status(BucketVersioningStatus.ENABLED)));
    }
    awslocal("events", "create-event-bus", "--name", BUS);
    var queue =
        awslocal(
            "sqs", "create-queue", "--queue-name", QUEUE, "--attributes", "VisibilityTimeout=900");
    queueUrl = JSON.readTree(queue).path("QueueUrl").asText();
    awslocal(
        "events",
        "put-rule",
        "--name",
        QUEUE,
        "--event-bus-name",
        BUS,
        "--event-pattern",
        "{\"source\":[\"uk.gov.companieshouse.address-lookup.ingest\"]}");
    awslocal(
        "events",
        "put-targets",
        "--rule",
        QUEUE,
        "--event-bus-name",
        BUS,
        "--targets",
        "Id=1,Arn=arn:aws:sqs:" + REGION + ":000000000000:" + QUEUE);
  }

  @Test
  @Order(1)
  void coldStartInitialisesSpringAndRecordsTheManifestOverIamTlsLogin() throws Exception {
    var release = new Release(2, 1);
    release.putManifest();

    var result = release.deliver("ok-manifest", release.manifestEvent());

    assertClean(result);
    assertEquals(2, release.manifestRows());
    assertTrue(
        LAMBDA.getLogs().contains("Upload processed"), "handler never logged a processed event");
  }

  @Test
  @Order(2)
  void islCsvLoadsLedgerMatchesCsvRowsAndRedeliveryAddsNothing() throws Exception {
    var release = new Release(2, 3);
    release.putManifest();
    release.putCsv(ISL, release.csv(ISL, 3));

    assertClean(release.deliver("ok-m", release.manifestEvent()));
    assertClean(release.deliver("ok-c", release.csvEvent(ISL)));
    assertClean(release.deliver("ok-c-again", release.csvEvent(ISL), release.manifestEvent()));

    assertEquals(3, release.landed(ISL));
    assertEquals(List.of(3L), release.ledger(ISL));
    assertEquals(2, release.manifestRows());
    assertTrue(release.rejections().isEmpty());
  }

  @Test
  @Order(3)
  void gbCsvLoadsWithBritishNationalGridGeometry() throws Exception {
    var release = new Release(4, 1);
    release.putManifest();
    release.putCsv(GB, release.csv(GB, 4));

    assertClean(release.deliver("ok-m", release.manifestEvent()));
    assertClean(release.deliver("ok-c", release.csvEvent(GB)));

    assertEquals(4, release.landed(GB));
    assertEquals(List.of(4L), release.ledger(GB));
    var geometry =
        DatasetCatalog.table(GB).columns().stream()
            .filter(c -> c.type().equals("geometry"))
            .findFirst()
            .orElseThrow()
            .name();
    assertEquals(
        List.of(27700),
        admin.queryForList(
            "SELECT DISTINCT ST_SRID("
                + geometry
                + ") FROM os_stage_full."
                + GB.datasetName()
                + " WHERE release_id=?::uuid",
            Integer.class,
            release.id));
  }

  @Test
  @Order(4)
  void fiftyThousandRowCsvStreamsFromS3WithinTheFunctionMemoryLimit() throws Exception {
    int rows = 50_000;
    var release = new Release(1, rows);
    release.putManifest();
    release.putCsv(ISL, release.csv(ISL, rows));

    assertClean(release.deliver("ok-m", release.manifestEvent()));
    assertClean(release.deliver("ok-big", release.csvEvent(ISL)));

    assertEquals(rows, release.landed(ISL));
    assertEquals(List.of((long) rows), release.ledger(ISL));
  }

  @Test
  @Order(5)
  void headerMismatchPublishesStepRejectedToEventBridgeAndWritesNothing() throws Exception {
    var release = new Release(2, 1);
    release.putManifest();
    release.putCsv(GB, "udprn,not_a_column\n1,x\n2,y\n");

    assertClean(release.deliver("ok-m", release.manifestEvent()));
    assertClean(release.deliver("expected-header", release.csvEvent(GB)));

    release.assertRejected(PermanentFailureException.HEADER_MISMATCH, GB);
    assertEquals(0, release.landed(GB));
    assertEquals(List.of(), release.ledger(GB));
  }

  @Test
  @Order(6)
  void rowCountDifferingFromTheManifestIsRejectedAndRolledBack() throws Exception {
    var release = new Release(5, 1);
    release.putManifest();
    release.putCsv(GB, release.csv(GB, 3));

    assertClean(release.deliver("ok-m", release.manifestEvent()));
    assertClean(release.deliver("expected-count", release.csvEvent(GB)));

    release.assertRejected(PermanentFailureException.ROW_COUNT_MISMATCH, GB);
    assertEquals(0, release.landed(GB));
    assertEquals(List.of(), release.ledger(GB));
  }

  @Test
  @Order(7)
  void malformedRowIsRejectedAndRolledBack() throws Exception {
    var release = new Release(2, 1);
    release.putManifest();
    var table = DatasetCatalog.table(GB);
    var lines = new ArrayList<>(List.of(release.csv(GB, 2).split("\n")));
    var cells =
        new ArrayList<>(Arrays.asList(lines.get(2).split(",(?=(?:[^\"]*\"[^\"]*\")*[^\"]*$)", -1)));
    int numeric = -1;
    for (int i = 0; i < table.columns().size(); i++) {
      if (table.columns().get(i).type().startsWith("numeric")) numeric = i;
    }
    assertTrue(numeric >= 0, "fixture needs a numeric column");
    cells.set(numeric, "not-a-number");
    lines.set(2, String.join(",", cells));
    release.putCsv(GB, String.join("\n", lines) + "\n");

    assertClean(release.deliver("ok-m", release.manifestEvent()));
    assertClean(release.deliver("expected-data", release.csvEvent(GB)));

    release.assertRejected(PermanentFailureException.INVALID_CSV_DATA, GB);
    assertEquals(0, release.landed(GB));
    assertEquals(List.of(), release.ledger(GB));
  }

  @Test
  @Order(8)
  void datasetMissingFromTheManifestIsRejected() throws Exception {
    var release = new Release(1, -1);
    release.putManifest();
    var other = ISL;
    release.putCsv(other, release.csv(other, 1));

    assertClean(release.deliver("ok-m", release.manifestEvent()));
    assertClean(release.deliver("expected-missing", release.csvEvent(other)));

    release.assertRejected(PermanentFailureException.DATASET_NOT_IN_RELEASE, other);
  }

  @Test
  @Order(9)
  void csvBeforeItsManifestIsRetriedThenLoadsOnceTheManifestArrives() throws Exception {
    var release = new Release(2, 1);
    release.putManifest();
    release.putCsv(GB, release.csv(GB, 2));

    var early = release.deliver("expected-early", release.csvEvent(GB));
    assertFalse(early.functionError());
    assertEquals(List.of("expected-early"), early.failedIds());
    assertEquals(0, release.landed(GB));
    assertTrue(release.rejections().isEmpty(), "a retryable condition must not reject the release");

    assertClean(release.deliver("ok-m", release.manifestEvent()));
    assertClean(release.deliver("ok-c", release.csvEvent(GB)));
    assertEquals(2, release.landed(GB));
  }

  @Test
  @Order(10)
  void pendingScanIsRetriedAndThreatsFoundIsRejected() throws Exception {
    var release = new Release(2, 1);
    release.putManifest();
    assertClean(release.deliver("ok-m", release.manifestEvent()));

    release.putCsv(GB, release.csv(GB, 2), "PENDING");
    var pending = release.deliver("expected-pending", release.csvEvent(GB));
    assertEquals(List.of("expected-pending"), pending.failedIds());
    assertTrue(release.rejections().isEmpty());
    assertEquals(0, release.landed(GB));

    release.putCsv(GB, release.csv(GB, 2), "THREATS_FOUND");
    assertClean(release.deliver("expected-threat", release.csvEvent(GB)));
    release.assertRejected(PermanentFailureException.SCAN_NOT_CLEAN, GB);
    assertEquals(0, release.landed(GB));
  }

  @Test
  @Order(11)
  void eventForASupersededObjectVersionIsAcknowledgedWithoutLoading() throws Exception {
    var release = new Release(2, 1);
    release.putManifest();
    assertClean(release.deliver("ok-m", release.manifestEvent()));

    release.putCsv(GB, release.csv(GB, 2));
    var staleEvent = release.csvEvent(GB);
    release.putCsv(GB, release.csv(GB, 2) + "9,x\n");

    assertClean(release.deliver("ok-stale", staleEvent));
    assertEquals(0, release.landed(GB));
    assertTrue(release.rejections().isEmpty());
  }

  @Test
  @Order(12)
  void releaseBlockedByQuarantineIsSkipped() throws Exception {
    var release = new Release(2, 1);
    release.putManifest();
    release.putCsv(GB, release.csv(GB, 2));
    s3.putObject(
        PutObjectRequest.builder().bucket(INGEST).key(ReleaseStore.blockedKey(release.id)).build(),
        RequestBody.fromString("{}"));

    assertClean(release.deliver("ok-m", release.manifestEvent()));
    assertClean(release.deliver("ok-c", release.csvEvent(GB)));

    assertEquals(0, release.manifestRows());
    assertEquals(0, release.landed(GB));
  }

  @Test
  @Order(13)
  void invalidManifestAndConflictingManifestAreRejected() throws Exception {
    var invalid = new Release(2, 1);
    invalid.putObject(INGEST, invalid.manifestKey(), "{not json".getBytes(StandardCharsets.UTF_8));
    assertClean(invalid.deliver("expected-invalid", invalid.manifestEvent()));
    invalid.assertRejected(PermanentFailureException.MANIFEST_INVALID, null);

    var conflict = new Release(2, 1);
    conflict.putManifest();
    assertClean(conflict.deliver("ok-m", conflict.manifestEvent()));
    var changed = new Release(conflict.id, 9, 1);
    changed.putManifest();
    assertClean(conflict.deliver("expected-conflict", changed.manifestEvent()));
    conflict.assertRejected(PermanentFailureException.MANIFEST_CONFLICT, null);
    assertEquals(2, conflict.manifestRows());
  }

  @Test
  @Order(14)
  void invalidEventsAreRejectedWithAReleaseIdAndDroppedWithout() throws Exception {
    var release = new Release(1, 1);
    var badVersion =
        "{\"detail-type\":\"DatasetReady\",\"detail\":{\"schemaVersion\":7,\"releaseId\":\""
            + release.id
            + "\",\"dataset\":\"x\",\"object\":{}}}";
    assertClean(release.deliver("expected-badversion", badVersion));
    release.assertRejected(PermanentFailureException.INVALID_EVENT, null);

    assertClean(invoke(message("expected-garbage", "this is not json")));
  }

  @Test
  @Order(15)
  void unexpectedInfrastructureFailureIsRetriedNeverRejected() throws Exception {
    var release = new Release(2, 1);
    release.putManifest();
    assertClean(release.deliver("ok-m", release.manifestEvent()));

    var result = release.deliver("expected-missing-object", release.csvEvent(GB));

    assertFalse(result.functionError());
    assertEquals(List.of("expected-missing-object"), result.failedIds());
    assertTrue(release.rejections().isEmpty());
  }

  @Test
  @Order(16)
  void oneRetryableRecordDoesNotFailOrBlockTheRestOfTheBatch() throws Exception {
    var release = new Release(2, 2);
    release.putManifest();
    release.putCsv(ISL, release.csv(ISL, 2));
    assertClean(release.deliver("ok-m", release.manifestEvent()));
    var notReady = new Release(1, 1);
    notReady.putCsv(GB, notReady.csv(GB, 1));

    var result =
        invoke(
            message("expected-batch-retry", notReady.csvEvent(GB)),
            message("ok-batch-good", release.csvEvent(ISL)));

    assertFalse(result.functionError());
    assertEquals(List.of("expected-batch-retry"), result.failedIds());
    assertEquals(2, release.landed(ISL));
  }

  @Test
  @Order(Integer.MAX_VALUE)
  void functionLogsContainNoUnexpectedErrorsOrCrashes() {
    var logs = LAMBDA.getLogs();
    var unexpected = new ArrayList<String>();
    for (String line : logs.split("\n")) {
      boolean error = line.contains("\"level\":\"ERROR\"") || line.contains("ERROR ");
      boolean crash =
          line.contains("OutOfMemoryError")
              || line.contains("errorType")
              || line.contains("Runtime exited")
              || line.contains("Task timed out");
      if ((error || crash) && !line.contains("expected-")) unexpected.add(line);
    }
    System.out.println("--- function REPORT lines ---");
    Arrays.stream(logs.split("\n"))
        .filter(l -> l.startsWith("REPORT"))
        .forEach(System.out::println);
    assertTrue(
        unexpected.isEmpty(), "Unexpected function log lines:\n" + String.join("\n", unexpected));
    assertFalse(
        Pattern.compile("password|token=|X-Amz-Signature", Pattern.CASE_INSENSITIVE)
            .matcher(logs)
            .find(),
        "credentials or IAM tokens must never be logged");
  }

  private static void assertClean(Invocation result) {
    assertFalse(result.functionError(), "function error: " + result.raw());
    assertEquals(List.of(), result.failedIds(), result.raw());
  }

  private static ObjectNode message(String id, String body) {
    var node = JSON.createObjectNode();
    node.put("messageId", id);
    node.put("body", body);
    return node;
  }

  private static Invocation invoke(ObjectNode... messages) throws Exception {
    var records = JSON.createArrayNode();
    Arrays.stream(messages).forEach(records::add);
    var event = JSON.createObjectNode();
    event.set("Records", records);
    var request =
        HttpRequest.newBuilder(
                URI.create(
                    "http://localhost:"
                        + LAMBDA.getMappedPort(8080)
                        + "/2015-03-31/functions/function/invocations"))
            .timeout(Duration.ofMinutes(3))
            .POST(HttpRequest.BodyPublishers.ofString(JSON.writeValueAsString(event)))
            .build();
    var response = HTTP.send(request, HttpResponse.BodyHandlers.ofString());
    boolean functionError = response.headers().firstValue("X-Amz-Function-Error").isPresent();
    var failed = new ArrayList<String>();
    if (!functionError) {
      for (var item : JSON.readTree(response.body()).path("batchItemFailures")) {
        failed.add(item.path("itemIdentifier").asText());
      }
    }
    return new Invocation(failed, functionError, response.body());
  }

  private static String awslocal(String... args) throws Exception {
    var command = new ArrayList<>(List.of("awslocal", "--region", REGION));
    command.addAll(Arrays.asList(args));
    var result = LOCALSTACK.execInContainer(command.toArray(String[]::new));
    assertEquals(0, result.getExitCode(), String.join(" ", command) + "\n" + result.getStderr());
    return result.getStdout();
  }

  private static synchronized List<JsonNode> drainRejections(boolean expectMore) throws Exception {
    int wait = expectMore ? 5 : 2;
    while (true) {
      var out =
          awslocal(
              "sqs",
              "receive-message",
              "--queue-url",
              queueUrl,
              "--max-number-of-messages",
              "10",
              "--wait-time-seconds",
              String.valueOf(wait));
      if (out.isBlank()) break;
      var messages = JSON.readTree(out).path("Messages");
      if (messages.isEmpty()) break;
      for (var message : messages) REJECTIONS.add(JSON.readTree(message.path("Body").asText()));
      wait = 1;
    }
    return List.copyOf(REJECTIONS);
  }

  private static final class Release {
    private final String id;
    private final long gbRows;
    private final long islRows;

    Release(long gbRows, long islRows) {
      this(UUID.randomUUID().toString(), gbRows, islRows);
    }

    Release(String id, long gbRows, long islRows) {
      this.id = id;
      this.gbRows = gbRows;
      this.islRows = islRows;
    }

    Invocation deliver(String messageId, String... events) throws Exception {
      return invoke(
          Arrays.stream(events)
              .map(e -> message(events.length == 1 ? messageId : messageId + "-" + e.length(), e))
              .toArray(ObjectNode[]::new));
    }

    String manifestKey() {
      return "acquisitions/" + TARGET + "/" + id + ".json";
    }

    void putManifest() throws Exception {
      var root = JSON.createObjectNode();
      root.put("releaseId", id);
      root.put("mode", "FULL");
      root.put("target", TARGET);
      root.put("dataPackageId", "1");
      root.put("versionId", "2");
      if (gbRows >= 0) root.set(GB.datasetName(), summary(GB, gbRows));
      if (islRows >= 0) root.set(ISL.datasetName(), summary(ISL, islRows));
      putObject(INGEST, manifestKey(), JSON.writeValueAsBytes(root));
    }

    void putCsv(Dataset dataset, String content) {
      putCsv(dataset, content, ReleaseStore.CLEAN);
    }

    void putCsv(Dataset dataset, String content, String scanStatus) {
      String key = ReleaseStore.extractedCsvKey(id, dataset.datasetName());
      var version =
          s3.putObject(
                  PutObjectRequest.builder().bucket(SCANNED).key(key).build(),
                  RequestBody.fromBytes(content.getBytes(StandardCharsets.UTF_8)))
              .versionId();
      tag(SCANNED, key, version, scanStatus);
    }

    void putObject(String bucket, String key, byte[] content) {
      var version =
          s3.putObject(
                  PutObjectRequest.builder().bucket(bucket).key(key).build(),
                  RequestBody.fromBytes(content))
              .versionId();
      tag(bucket, key, version, ReleaseStore.CLEAN);
    }

    private static void tag(String bucket, String key, String version, String status) {
      s3.putObjectTagging(b ->
          b.bucket(bucket)
              .key(key)
              .versionId(version)
              .tagging(
                  Tagging.builder()
                      .tagSet(Tag.builder().key("GuardDutyMalwareScanStatus").value(status).build())
                      .build()));
    }

    String manifestEvent() {
      return """
      {"detail-type":"ManifestReady","detail":{"schemaVersion":1,"releaseId":"%s",
      "object":{"bucket":"%s","key":"%s","versionId":"%s"}}}
      """
          .formatted(id, INGEST, manifestKey(), currentVersion(INGEST, manifestKey()));
    }

    String csvEvent(Dataset dataset) {
      String key = ReleaseStore.extractedCsvKey(id, dataset.datasetName());
      return """
      {"detail-type":"DatasetReady","detail":{"schemaVersion":1,"releaseId":"%s","dataset":"%s",
      "object":{"bucket":"%s","key":"%s","versionId":"%s"}}}
      """
          .formatted(id, dataset.datasetName(), SCANNED, key, currentVersion(SCANNED, key));
    }

    private static String currentVersion(String bucket, String key) {
      try {
        return s3.headObject(b -> b.bucket(bucket).key(key)).versionId();
      } catch (Exception e) {
        return "no-such-version";
      }
    }

    List<JsonNode> rejections() throws Exception {
      return drainRejections(false).stream()
          .map(e -> e.path("detail"))
          .filter(d -> id.equals(d.path("releaseId").asText()))
          .toList();
    }

    void assertRejected(String code, Dataset dataset) throws Exception {
      List<JsonNode> found = List.of();
      for (int attempt = 0; attempt < 6 && found.isEmpty(); attempt++) {
        found =
            drainRejections(true).stream()
                .map(e -> e.path("detail"))
                .filter(d -> id.equals(d.path("releaseId").asText()))
                .toList();
      }
      assertEquals(1, found.size(), "StepRejected events for " + id + ": " + found);
      var detail = found.get(0);
      assertEquals(code, detail.path("code").asText());
      assertEquals("upload", detail.path("step").asText());
      if (dataset != null) assertEquals(dataset.datasetName(), detail.path("dataset").asText());
    }

    int landed(Dataset dataset) {
      return JdbcClient.create(admin)
          .sql(
              "SELECT count(*) FROM os_stage_full."
                  + dataset.datasetName()
                  + " WHERE release_id=?::uuid")
          .param(id)
          .query(Integer.class)
          .single();
    }

    List<Long> ledger(Dataset dataset) {
      return admin.queryForList(
          "SELECT row_count FROM os_ingest.import_ledger WHERE release_id=?::uuid AND dataset=?",
          Long.class,
          id,
          dataset.datasetName());
    }

    int manifestRows() {
      return JdbcClient.create(admin)
          .sql("SELECT count(*) FROM os_ingest.data_package_manifest WHERE release_id=?::uuid")
          .param(id)
          .query(Integer.class)
          .single();
    }

    String csv(Dataset dataset, int rows) {
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

    private static ObjectNode summary(Dataset dataset, long rows) {
      var file = JSON.createObjectNode();
      var zip = JSON.createObjectNode();
      zip.put("fileName", "a.zip");
      zip.put("url", "https://x");
      zip.put("md5", "229c68b69090208861bc3fdf05009ef6");
      file.set("zip", zip);
      var summary = JSON.createObjectNode();
      summary.set("featureName", TextNode.valueOf(dataset.datasetName().replace('_', '-')));
      summary.set(
          "crs",
          TextNode.valueOf(
              "http://www.opengis.net/def/crs/EPSG/0/" + (dataset == ISL ? 4258 : 27700)));
      summary.set("schemaVersion", TextNode.valueOf(DatasetCatalog.table(dataset).version()));
      summary.putNull("validFromDate");
      summary.put("validToDate", TARGET);
      summary.put("recordCount", rows);
      file.set("summary", summary);
      return file;
    }
  }
}
