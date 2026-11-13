package db.local.java;

import java.io.FilterInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.Reader;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.WeakHashMap;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutionException;
import java.util.stream.Collectors;
import javax.sql.DataSource;
import org.flywaydb.core.api.MigrationVersion;
import org.flywaydb.core.api.migration.Context;
import org.flywaydb.core.api.migration.JavaMigration;
import org.postgresql.PGConnection;
import uk.gov.companieshouse.logging.Logger;
import uk.gov.companieshouse.logging.LoggerFactory;

/**
 * Local-only repeatable migration that bulk-loads a Royal Mail CSV (downloaded from the OS API and
 * never committed) into a v2 table with release_id 'local'. Registered only through the local
 * Flyway location db/local/java.
 */
public abstract class LocalRoyalMailCsvSeed implements JavaMigration {

  public static final String SEED_DIR_PROPERTY = "als.local.seed.dir";
  public static final String SEED_DIR_ENV = "LOCAL_SEED_DIR";
  static final String LOCAL_RELEASE_ID = "local";
  private static final String SCHEMA = "os_data";
  private static final String FORMAT_SQL = "SELECT format(?, VARIADIC ?)";
  private static final String DEFAULT_SEED_DIR = "database/local/data";

  private static final Logger LOG = LoggerFactory.getLogger(LocalRoyalMailCsvSeed.class.getName());

  // Checksums of datasets already loaded in parallel by a sibling seed, per Flyway instance.
  private static final Map<Object, Map<String, Integer>> LOADED_BY_SIBLING =
      Collections.synchronizedMap(new WeakHashMap<>());

  private final String dataset;
  private final LocalRoyalMailCsvSeed sibling;

  protected LocalRoyalMailCsvSeed(String dataset) {
    this(dataset, null);
  }

  /** The sibling is loaded concurrently on its own connection while this seed loads. */
  protected LocalRoyalMailCsvSeed(String dataset, LocalRoyalMailCsvSeed sibling) {
    this.sibling = sibling;
    if (!dataset.matches("[a-z][a-z0-9_]*")) {
      throw new IllegalArgumentException("Unsafe dataset name: " + dataset);
    }
    this.dataset = dataset;
  }

  /** Resolves the CSV directory: system property, then LOCAL_SEED_DIR, then database/local/data. */
  static Path seedDirectory() {
    String configured = System.getProperty(SEED_DIR_PROPERTY, System.getenv(SEED_DIR_ENV));
    if (configured != null && !configured.isBlank()) {
      return Path.of(configured);
    }
    Path local = Path.of(DEFAULT_SEED_DIR);
    return Files.isDirectory(local) ? local : Path.of("..").resolve(DEFAULT_SEED_DIR);
  }

  private Path csv() {
    return seedDirectory().resolve(dataset + ".csv");
  }

  private Path version() {
    return seedDirectory().resolve(dataset + ".csv.version");
  }

  @Override
  public MigrationVersion getVersion() {
    return null;
  }

  @Override
  public String getDescription() {
    return "seed " + dataset.replace('_', ' ') + " csv";
  }

  /** Changes whenever the downloader records a different OS version for the CSV. */
  @Override
  public Integer getChecksum() {
    try {
      if (!Files.exists(csv())) {
        return 0;
      }
      // A CSV without a version marker (such as a supplied fixture) still differs from "absent".
      return Files.exists(version())
          ? Files.readString(version(), StandardCharsets.UTF_8).trim().hashCode()
          : 1;
    } catch (IOException e) {
      return 0;
    }
  }

  @Override
  public boolean canExecuteInTransaction() {
    return true;
  }

  /**
   * Replaces the local rows: deletes release_id 'local', stages the CSV with COPY into a temporary
   * text table, then inserts with casts taken from the target column types and a geometry rebuilt
   * from its WKT. Skips with a warning when the CSV is absent. A sibling seed, when present, loads
   * at the same time on its own connection and transaction.
   */
  @Override
  public void migrate(Context context) throws IOException, SQLException, InterruptedException {
    Path file = csv();
    if (!Files.isRegularFile(file)) {
      LOG.info("Skipping local seed for %s: %s not found".formatted(dataset, file));
      return;
    }
    Object run = context.getConfiguration();
    Map<String, Integer> done = LOADED_BY_SIBLING.get(run);
    Integer loadedChecksum = done == null ? null : done.remove(dataset);
    // A marker left by an earlier run for another checksum is stale and must not skip this load.
    if (loadedChecksum != null && loadedChecksum.equals(getChecksum())) {
      LOG.info("Seed %s: already loaded in parallel with a sibling seed".formatted(dataset));
      return;
    }
    CompletableFuture<Void> parallel = null;
    if (sibling != null && Files.isRegularFile(sibling.csv())) {
      DataSource dataSource = context.getConfiguration().getDataSource();
      LOADED_BY_SIBLING
          .computeIfAbsent(run, k -> new ConcurrentHashMap<>())
          .put(sibling.dataset, sibling.getChecksum());
      parallel = CompletableFuture.runAsync(() -> sibling.loadOnOwnConnection(dataSource));
    }
    try {
      load(context.getConnection());
    } finally {
      if (parallel != null) {
        awaitSibling(run, parallel);
      }
    }
  }

  private void awaitSibling(Object run, CompletableFuture<Void> parallel)
      throws InterruptedException {
    try {
      parallel.get();
    } catch (ExecutionException e) {
      LOADED_BY_SIBLING.get(run).remove(sibling.dataset);
      throw new IllegalStateException("Parallel seed " + sibling.dataset + " failed", e.getCause());
    }
  }

  private void loadOnOwnConnection(DataSource dataSource) {
    try (Connection connection = dataSource.getConnection()) {
      connection.setAutoCommit(false);
      loadAndCommit(connection);
    } catch (IOException | SQLException e) {
      throw new IllegalStateException(e);
    }
  }

  private void loadAndCommit(Connection connection) throws IOException, SQLException {
    try {
      load(connection);
      connection.commit();
    } catch (IOException | SQLException | RuntimeException e) {
      connection.rollback();
      throw e;
    }
  }

  private void load(Connection connection) throws IOException, SQLException {
    Path file = csv();
    // Every identifier spliced into the SQL below is a validated constant: the dataset name is
    // checked in the constructor and each CSV column against [a-z_][a-z0-9_]* and the target table.
    String table = "os_data." + dataset + "_v2";
    String stage = "stage_" + dataset;
    try (Statement statement = connection.createStatement()) {
      statement.execute(
          format(
              connection,
              "DELETE FROM %I.%I WHERE release_id = %L",
              SCHEMA,
              dataset + "_v2",
              LOCAL_RELEASE_ID));
    }
    List<String> header = readHeader(file);
    List<String> targetColumns = targetColumns(connection, table);
    for (String column : header) {
      if (!targetColumns.contains(column)) {
        throw new IllegalStateException("CSV column " + column + " is not in " + table);
      }
    }
    String columnList = header.stream().collect(Collectors.joining(", "));
    List<String> createArgs = new ArrayList<>();
    createArgs.add(stage);
    createArgs.addAll(header);
    String createTemplate =
        "CREATE TEMP TABLE %I ("
            + header.stream().map(c -> "%I text").collect(Collectors.joining(", "))
            + ") ON COMMIT DROP";
    try (Statement statement = connection.createStatement()) {
      statement.execute(format(connection, createTemplate, createArgs.toArray(String[]::new)));
    }
    LOG.info("Seed %s: staging %s ...".formatted(dataset, file));
    try (Reader reader =
        new InputStreamReader(
            new ProgressInputStream(Files.newInputStream(file), Files.size(file), dataset),
            StandardCharsets.UTF_8)) {
      long rows =
          connection
              .unwrap(PGConnection.class)
              .getCopyAPI()
              .copyIn(
                  "COPY "
                      + stage
                      + " ("
                      + columnList
                      + ") FROM STDIN WITH (FORMAT csv, HEADER true, FORCE_NULL ("
                      + columnList
                      + "))",
                  reader);
      LOG.info("Staged %d rows for %s".formatted(rows, dataset));
    }
    String srid = resolveSrid(connection, table);
    List<String> insertArgs = new ArrayList<>(List.of(SCHEMA, dataset + "_v2"));
    insertArgs.addAll(header);
    insertArgs.add(LOCAL_RELEASE_ID);
    StringBuilder select = new StringBuilder();
    for (String column : header) {
      select.append(select.isEmpty() ? "" : ", ");
      if ("geometry".equals(column)) {
        select.append("ST_GeomFromText(s.geometry, %s)");
        insertArgs.add(srid);
      } else {
        select.append("s.%I::%s");
        insertArgs.add(column);
        insertArgs.add(columnType(connection, table, column));
      }
    }
    insertArgs.add(stage);
    String insertTemplate =
        "INSERT INTO %I.%I (release_id, "
            + header.stream().map(c -> "%I").collect(Collectors.joining(", "))
            + ") SELECT %L, "
            + select
            + " FROM %I s ON CONFLICT (udprn) DO NOTHING";
    LOG.info(
        "Seed %s: inserting staged rows into %s (typing and geometry) ..."
            .formatted(dataset, table));
    try (Statement statement = connection.createStatement()) {
      int inserted =
          statement.executeUpdate(
              format(connection, insertTemplate, insertArgs.toArray(String[]::new)));
      LOG.info("Seeded %d local rows into %s".formatted(inserted, table));
    }
  }

  /**
   * Lets PostgreSQL quote identifiers (%I) and literals (%L) so no SQL is assembled by string
   * concatenation here.
   */
  private static String format(Connection connection, String template, String... args)
      throws SQLException {
    try (PreparedStatement statement = connection.prepareStatement(FORMAT_SQL)) {
      statement.setString(1, template);
      statement.setArray(2, connection.createArrayOf("text", args));
      try (ResultSet rs = statement.executeQuery()) {
        rs.next();
        return rs.getString(1);
      }
    }
  }

  /** The SRID is read from the target geometry column, not hardcoded. */
  private String resolveSrid(Connection connection, String table) throws SQLException {
    try (PreparedStatement statement =
        connection.prepareStatement(
            "SELECT srid FROM geometry_columns WHERE f_table_schema = 'os_data' "
                + "AND f_table_name = ? AND f_geometry_column = 'geometry'")) {
      statement.setString(1, dataset + "_v2");
      try (ResultSet rs = statement.executeQuery()) {
        if (!rs.next()) {
          throw new IllegalStateException("No geometry column in " + table);
        }
        return String.valueOf(rs.getInt(1));
      }
    }
  }

  private static List<String> readHeader(Path file) throws IOException {
    try (var lines = Files.lines(file, StandardCharsets.UTF_8)) {
      String first = lines.findFirst().orElseThrow(() -> new IllegalStateException("Empty CSV"));
      List<String> columns = new ArrayList<>();
      for (String column : first.strip().split(",")) {
        String name = column.strip();
        if (!name.matches("[a-z_][a-z0-9_]*")) {
          throw new IllegalStateException("Unexpected CSV column name: " + name);
        }
        columns.add(name);
      }
      return columns;
    }
  }

  private static List<String> targetColumns(Connection connection, String table)
      throws SQLException {
    List<String> columns = new ArrayList<>();
    try (Statement statement = connection.createStatement();
        ResultSet rs = statement.executeQuery(columnQuery(table))) {
      while (rs.next()) {
        columns.add(rs.getString(1));
      }
    }
    return columns;
  }

  private static String columnType(Connection connection, String table, String column) {
    try (Statement statement = connection.createStatement();
        ResultSet rs = statement.executeQuery(columnQuery(table))) {
      while (rs.next()) {
        if (Objects.equals(rs.getString(1), column)) {
          return rs.getString(2);
        }
      }
    } catch (SQLException e) {
      throw new IllegalStateException(e);
    }
    throw new IllegalStateException("No column " + column + " in " + table);
  }

  private static String columnQuery(String table) {
    return "SELECT attname, format_type(atttypid, atttypmod) FROM pg_attribute "
        + "WHERE attrelid = '"
        + table
        + "'::regclass AND attnum > 0 AND NOT attisdropped";
  }

  /** Logs "Seed <dataset>: staging NN%" each time another 5% of the file has been read. */
  static final class ProgressInputStream extends FilterInputStream {
    private final long total;
    private final String name;
    private long read;
    private int lastStep = -1;

    ProgressInputStream(InputStream in, long total, String name) {
      super(in);
      this.total = Math.max(total, 1);
      this.name = name;
    }

    @Override
    public int read() throws IOException {
      int value = super.read();
      if (value >= 0) {
        advance(1);
      }
      return value;
    }

    @Override
    public int read(byte[] buffer, int offset, int length) throws IOException {
      int count = super.read(buffer, offset, length);
      if (count > 0) {
        advance(count);
      }
      return count;
    }

    private void advance(long count) {
      read += count;
      int step = (int) Math.min(100, read * 100 / total) / 5 * 5;
      if (step > lastStep) {
        lastStep = step;
        LOG.info(
            "Seed %s: staging %d%% (%d of %d MB)".formatted(name, step, read >> 20, total >> 20));
      }
    }
  }
}
