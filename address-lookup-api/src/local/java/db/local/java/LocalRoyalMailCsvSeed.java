package db.local.java;

import org.flywaydb.core.api.migration.BaseJavaMigration;
import org.flywaydb.core.api.migration.Context;
import org.postgresql.PGConnection;

import uk.gov.companieshouse.logging.Logger;
import uk.gov.companieshouse.logging.LoggerFactory;

import java.io.FilterInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.OutputStream;
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
import java.util.List;
import java.util.Objects;
import java.util.stream.Collectors;
import java.util.zip.CRC32;
import java.util.zip.CheckedInputStream;

/**
 * Local-only repeatable migration that bulk-loads a Royal Mail CSV (downloaded from the OS API and
 * never committed) into a v2 table with release_id 'local'. Registered only through the local
 * Flyway location db/local/java.
 */
public abstract class LocalRoyalMailCsvSeed extends BaseJavaMigration {

    public static final String SEED_DIR_PROPERTY = "als.local.seed.dir";
    public static final String SEED_DIR_ENV = "LOCAL_SEED_DIR";
    static final String LOCAL_RELEASE_ID = "local";
    private static final String SCHEMA = "os_data";
    private static final String FORMAT_SQL = "SELECT format(?, VARIADIC ?)";
    private static final String DEFAULT_SEED_DIR = "database/local/data";

    private static final Logger LOG =
            LoggerFactory.getLogger(LocalRoyalMailCsvSeed.class.getName());

    private final String dataset;
    private final String xColumn;
    private final String yColumn;

    /**
     * The geometry is built from the xColumn and yColumn coordinates (easting and northing for
     * British National Grid, longitude and latitude for ETRS89), not from the CSV geometry column.
     */
    protected LocalRoyalMailCsvSeed(String dataset, String xColumn, String yColumn) {
        for (String name : List.of(dataset, xColumn, yColumn)) {
            if (!name.matches("[a-z][a-z0-9_]*")) {
                throw new IllegalArgumentException("Unsafe name: " + name);
            }
        }
        this.dataset = dataset;
        this.xColumn = xColumn;
        this.yColumn = yColumn;
    }

    /**
     * Resolves the CSV directory: system property, then LOCAL_SEED_DIR, then database/local/data.
     */
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

    /** The MD5 of the OS ZIP, as listed by the OS download feed and recorded by the downloader. */
    private Path versionMarker() {
        return seedDirectory().resolve(dataset + ".csv.version");
    }

    /**
     * Uses the version marker written by the downloader (package and version id) when there is one,
     * so a new OS version re-applies the seed. Without a marker, such as the committed fixtures, it
     * hashes the CSV contents, so an edited CSV re-applies it. Returns 0 when the CSV is absent.
     */
    @Override
    public Integer getChecksum() {
        try {
            if (!Files.exists(csv())) {
                return 0;
            }
            if (Files.exists(versionMarker())) {
                return Files.readString(versionMarker(), StandardCharsets.UTF_8).trim().hashCode();
            }
            CRC32 crc = new CRC32();
            try (InputStream in = new CheckedInputStream(Files.newInputStream(csv()), crc)) {
                in.transferTo(OutputStream.nullOutputStream());
            }
            return (int) crc.getValue();
        } catch (IOException e) {
            return 0;
        }
    }

    /**
     * Replaces the local rows in the migration's own transaction: deletes release_id 'local',
     * stages the CSV with COPY into a temporary text table, then inserts it with casts taken from
     * the target column types, a geometry built from the coordinate columns, and duplicate udprn
     * rows removed. Skips when the CSV is absent.
     */
    @Override
    public void migrate(Context context) throws IOException, SQLException {
        Path file = csv();
        if (!Files.isRegularFile(file)) {
            LOG.info("Skipping local seed for %s: %s not found".formatted(dataset, file));
            return;
        }
        load(context.getConnection());
    }

    private void load(Connection connection) throws IOException, SQLException {
        Path file = csv();
        // Every identifier spliced into the SQL below is a validated constant: the dataset name is
        // checked in the constructor and each CSV column against [a-z_][a-z0-9_]* and the target
        // table.
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
        for (String required : List.of("udprn", xColumn, yColumn)) {
            if (!header.contains(required)) {
                throw new IllegalStateException(
                        "CSV column " + required + " is required for " + dataset);
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
            statement.execute(
                    format(connection, createTemplate, createArgs.toArray(String[]::new)));
        }
        LOG.info("Seed %s: staging %s ...".formatted(dataset, file));
        long staged;
        try (Reader reader =
                new InputStreamReader(
                        new ProgressInputStream(
                                Files.newInputStream(file), Files.size(file), dataset),
                        StandardCharsets.UTF_8)) {
            staged =
                    connection
                            .unwrap(PGConnection.class)
                            .getCopyAPI()
                            .copyIn(
                                    "COPY "
                                            + stage
                                            + " ("
                                            + columnList
                                            + ") FROM STDIN WITH (FORMAT csv, HEADER true,"
                                            + " FORCE_NULL ("
                                            + columnList
                                            + "))",
                                    reader);
            LOG.info("Staged %d rows for %s".formatted(staged, dataset));
        }
        String srid = resolveSrid(connection, table);
        List<String> insertArgs = new ArrayList<>(List.of(SCHEMA, dataset + "_v2"));
        insertArgs.addAll(header);
        insertArgs.add(LOCAL_RELEASE_ID);
        StringBuilder select = new StringBuilder();
        for (String column : header) {
            select.append(select.isEmpty() ? "" : ", ");
            if ("geometry".equals(column)) {
                select.append(
                        "ST_SetSRID(ST_MakePoint(s.%I::double precision, s.%I::double precision),"
                            + " %s)");
                insertArgs.add(xColumn);
                insertArgs.add(yColumn);
                insertArgs.add(srid);
            } else {
                select.append("s.%I::%s");
                insertArgs.add(column);
                insertArgs.add(columnType(connection, table, column));
            }
        }
        insertArgs.add(stage);
        // Every row is staged first; duplicate udprn rows are removed only here, keeping the
        // newest.
        StringBuilder newest = new StringBuilder();
        for (String column : List.of("versiondate", "entrydate")) {
            if (header.contains(column)) {
                newest.append(", %I DESC NULLS LAST");
                insertArgs.add(column);
            }
        }
        String insertTemplate =
                "INSERT INTO %I.%I (release_id, "
                        + header.stream().map(c -> "%I").collect(Collectors.joining(", "))
                        + ") SELECT %L, "
                        + select
                        + " FROM (SELECT DISTINCT ON (udprn) * FROM %I ORDER BY udprn"
                        + newest
                        + ") s";
        LOG.info(
                "Seed %s: inserting staged rows into %s (typing, geometry, de-duplication) ..."
                        .formatted(dataset, table));
        try (Statement statement = connection.createStatement()) {
            int inserted =
                    statement.executeUpdate(
                            format(connection, insertTemplate, insertArgs.toArray(String[]::new)));
            LOG.info(
                    "Seeded %d local rows into %s (%d duplicate udprn rows removed)"
                            .formatted(inserted, table, staged - inserted));
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
            String first =
                    lines.findFirst().orElseThrow(() -> new IllegalStateException("Empty CSV"));
            List<String> columns = new ArrayList<>();
            for (String column : first.replace("\uFEFF", "").strip().split(",")) {
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
                        "Seed %s: staging %d%% (%d of %d MB)"
                                .formatted(name, step, read >> 20, total >> 20));
            }
        }
    }
}
