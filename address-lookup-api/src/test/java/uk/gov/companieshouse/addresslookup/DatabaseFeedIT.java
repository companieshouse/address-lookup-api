package uk.gov.companieshouse.addresslookup;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
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
import org.flywaydb.core.Flyway;
import org.flywaydb.core.api.output.MigrateResult;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.postgresql.PostgreSQLContainer;
import org.testcontainers.utility.DockerImageName;

/**
 * Verifies the root Flyway database feed and its local seed migrations.
 */
@Testcontainers
class DatabaseFeedIT {

    private static final List<String> TABLES = List.of(
            "add_gb_royalmailaddress_v2",
            "add_isl_royalmailaddress_v2");
    private static final List<String> OFFICIAL_COLUMNS = List.of(
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
    private static final Map<String, String> COLUMN_TYPES = Map.ofEntries(
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
    private static final Set<String> NULLABLE_COLUMNS = Set.of(
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
    private static final Set<String> HAPPY_PATH_POSTCODES = Set.of(
            "WF27QD",
            "AB101AU",
            "BT100BU",
            "BT100EQ",
            "BT513TU",
            "GY80EE",
            "GY79AD",
            "IM11BD",
            "JE27ND",
            "JE27NA");
    private static final Map<String, Integer> NULL_COUNTRY_FIXTURES = Map.of(
            "add_gb_royalmailaddress_v2", 19,
            "add_isl_royalmailaddress_v2", 10403188);

    @SuppressWarnings("resource")
    @Container
    private static final PostgreSQLContainer POSTGRES =
            new PostgreSQLContainer(
                    DockerImageName.parse("postgis/postgis:18-3.6")
                            .asCompatibleSubstituteFor("postgres"))
                    .withCreateContainerCmdModifier(
                            command -> command.withPlatform("linux/amd64"));

    /**
     * Checks fresh production migrations, local fixtures, and repeatable seed reruns.
     */
    @Test
    void migratesAndSeedsFreshPostgisDatabaseRepeatably(@TempDir Path tempSeeds)
            throws IOException, SQLException {
        Path databaseRoot = repositoryRoot().resolve("database");
        Flyway productionFlyway = flyway(databaseRoot.resolve("migrations"));

        MigrateResult productionResult = productionFlyway.migrate();
        assertEquals(4, productionResult.migrationsExecuted);
        productionFlyway.validate();
        assertEquals(0, productionFlyway.migrate().migrationsExecuted);

        assertProductionSchema();

        Flyway localFlyway = flyway(databaseRoot.resolve("migrations"),
                databaseRoot.resolve("local"));
        assertEquals(2, localFlyway.migrate().migrationsExecuted);
        assertLocalFixtures();

        Map<String, Long> localRowCounts = new HashMap<>();
        for (String table : TABLES) {
            localRowCounts.put(table, rowCount(table, "local"));
            addNonLocalSentinel(table);
        }

        copySeedsWithChangedChecksums(databaseRoot.resolve("local"), tempSeeds);
        Flyway changedSeedFlyway = flyway(databaseRoot.resolve("migrations"), tempSeeds);
        assertEquals(2, changedSeedFlyway.migrate().migrationsExecuted);
        changedSeedFlyway.validate();

        for (String table : TABLES) {
            assertEquals(localRowCounts.get(table), rowCount(table, "local"));
            assertEquals(1, rowCount(table, "preserve-test"));
            assertEquals(1, countDistinctUdprns(table, "preserve-test"));
        }
        assertLocalFixtures();
    }

    private static Flyway flyway(Path... locations) {
        return Flyway.configure()
                .dataSource(POSTGRES.getJdbcUrl(), POSTGRES.getUsername(), POSTGRES.getPassword())
                .locations(List.of(locations).stream()
                        .map(path -> "filesystem:" + path.toAbsolutePath())
                        .toArray(String[]::new))
                .load();
    }

    private static Path repositoryRoot() {
        Path candidate = Path.of("").toAbsolutePath();
        while (candidate != null
                && !Files.isDirectory(candidate.resolve("database/migrations"))) {
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
                assertRelation(connection, "os_data."
                        + table.replace("_v2", "_v1"), false);
                assertPhysicalColumns(connection, table);
                assertPrimaryKey(connection, table);
                assertPostcodeIndex(connection, table);
            }
            assertEquals(Set.copyOf(TABLES), physicalTableNames(connection));

            Map<String, String> contracts = new HashMap<>();
            try (Statement statement = connection.createStatement();
                    ResultSet rows = statement.executeQuery(
                            "SELECT dataset, version FROM os_control.schema_contract")) {
                while (rows.next()) {
                    contracts.put(rows.getString("dataset"), rows.getString("version"));
                }
            }
            assertEquals(Map.of(
                    "add_gb_royalmailaddress", "2.0",
                    "add_isl_royalmailaddress", "2.0"), contracts);
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

    private static void assertPhysicalColumns(Connection connection, String table)
            throws SQLException {
        List<String> columns = new ArrayList<>();
        Map<String, String> types = new HashMap<>();
        Map<String, Boolean> nullable = new HashMap<>();
        try (PreparedStatement statement = connection.prepareStatement(
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
        COLUMN_TYPES.keySet().forEach(
                column -> expectedNullability.put(column, expectedNullableColumns.contains(column)));
        assertEquals(expectedNullability, nullable, "OS v2 nullability differs for " + table);
    }

    private static String columnType(ResultSet result) throws SQLException {
        return switch (result.getString("data_type")) {
            case "character varying" -> "varchar("
                    + result.getInt("character_maximum_length") + ")";
            case "numeric" -> "numeric("
                    + result.getInt("numeric_precision") + ","
                    + result.getInt("numeric_scale") + ")";
            case "USER-DEFINED" -> result.getString("udt_name");
            default -> result.getString("data_type");
        };
    }

    private static Set<String> physicalTableNames(Connection connection) throws SQLException {
        Set<String> names = new HashSet<>();
        try (Statement statement = connection.createStatement();
                ResultSet result = statement.executeQuery(
                        "SELECT table_name FROM information_schema.tables "
                                + "WHERE table_schema = 'os_data' AND table_type = 'BASE TABLE'")) {
            while (result.next()) {
                names.add(result.getString(1));
            }
        }
        return names;
    }

    private static void assertPostcodeIndex(Connection connection, String table)
            throws SQLException {
        String indexName = table + "_postcode_idx";
        try (PreparedStatement statement = connection.prepareStatement(
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
                String expression = result.getString("expression")
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

    private static void assertPrimaryKey(Connection connection, String table)
            throws SQLException {
        try (PreparedStatement statement = connection.prepareStatement(
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
        Set<String> expected = Set.of(
                "add_gb_royalmailaddress|add_gb_royalmailaddress_v2|"
                        + "add_gb_royalmailaddress_v2_pkey|primary_key|udprn",
                "add_gb_royalmailaddress|add_gb_royalmailaddress_v2|"
                        + "add_gb_royalmailaddress_v2_postcode_idx|index|"
                        + "upper(replace(postcode, ' ', ''))",
                "add_isl_royalmailaddress|add_isl_royalmailaddress_v2|"
                        + "add_isl_royalmailaddress_v2_pkey|primary_key|udprn",
                "add_isl_royalmailaddress|add_isl_royalmailaddress_v2|"
                        + "add_isl_royalmailaddress_v2_postcode_idx|index|"
                        + "upper(replace(postcode, ' ', ''))");
        Set<String> actual = new HashSet<>();
        try (Statement statement = connection.createStatement();
                ResultSet result = statement.executeQuery(
                        "SELECT dataset, table_name, index_name, index_type, definition "
                                + "FROM os_control.index_definition")) {
            while (result.next()) {
                actual.add(String.join("|",
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
        try (Connection connection = connection()) {
            for (String postcode : HAPPY_PATH_POSTCODES) {
                try (PreparedStatement statement = connection.prepareStatement(
                        "SELECT count(*) FROM address_lookup.address_lookup "
                                + "WHERE upper(replace(postcode, ' ', '')) = ?")) {
                    statement.setString(1, postcode);
                    try (ResultSet result = statement.executeQuery()) {
                        assertTrue(result.next());
                        assertTrue(result.getLong(1) > 0, "Missing postcode fixture " + postcode);
                    }
                }
            }
            assertTrue(countSource(connection, "GB") > 0, "No GB view rows");
            assertTrue(countSource(connection, "ISL") > 0, "No ISL view rows");
            assertViewSourceForPostcode(connection, "WF27QD", "GB");
            assertViewSourceForPostcode(connection, "GY79AD", "ISL");
            assertEquals(0, countInvalidSourceIds(connection));
            assertRegionGeometry(connection, TABLES.get(0), 27700, true);
            assertRegionGeometry(connection, TABLES.get(1), 4258, false);
            for (Map.Entry<String, Integer> fixture : NULL_COUNTRY_FIXTURES.entrySet()) {
                assertNullCountryFixture(connection, fixture.getKey(), fixture.getValue());
            }
        }
    }

    private static void assertNullCountryFixture(
            Connection connection, String table, int udprn) throws SQLException {
        try (PreparedStatement statement = connection.prepareStatement(
                "SELECT country, release_id FROM os_data." + table + " WHERE udprn = ?")) {
            statement.setInt(1, udprn);
            try (ResultSet result = statement.executeQuery()) {
                assertTrue(result.next(), "Missing approved NULL-country fixture " + udprn);
                assertNull(result.getString("country"));
                assertEquals("local", result.getString("release_id"));
                assertFalse(result.next(), "Duplicate fixture " + udprn);
            }
        }
    }

    private static void assertNullableColumn(
            Connection connection, String table, String column, boolean nullable)
            throws SQLException {
        try (PreparedStatement statement = connection.prepareStatement(
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
        try (PreparedStatement statement = connection.prepareStatement(
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
        try (PreparedStatement statement = connection.prepareStatement(
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
        try (PreparedStatement statement = connection.prepareStatement(
                "SELECT count(*) FROM address_lookup.address_lookup "
                        + "WHERE upper(replace(postcode, ' ', '')) = ? AND address_source = ? "
                        + "AND id LIKE ?")) {
            statement.setString(1, postcode);
            statement.setString(2, source);
            statement.setString(3, source + "-%");
            try (ResultSet result = statement.executeQuery()) {
                assertTrue(result.next());
                assertTrue(result.getLong(1) > 0,
                        "No correctly labelled " + source + " rows for " + postcode);
            }
        }
    }

    private static long countInvalidSourceIds(Connection connection) throws SQLException {
        try (Statement statement = connection.createStatement();
                ResultSet result = statement.executeQuery(
                        "SELECT count(*) FROM address_lookup.address_lookup "
                                + "WHERE id <> address_source || '-' || udprn::text")) {
            assertTrue(result.next());
            return result.getLong(1);
        }
    }

    private static void assertRegionGeometry(
            Connection connection, String table, int srid, boolean gb) throws SQLException {
        String invalidGeometry = gb
                ? "ST_SRID(geometry) <> ? OR easting IS NULL OR northing IS NULL "
                        + "OR abs(ST_X(geometry) - easting) > 0.01 "
                        + "OR abs(ST_Y(geometry) - northing) > 0.01"
                : "ST_SRID(geometry) <> ? "
                        + "OR abs(ST_X(geometry) - longitude) > 0.0000001 "
                        + "OR abs(ST_Y(geometry) - latitude) > 0.0000001";
        try (PreparedStatement statement = connection.prepareStatement(
                "SELECT count(*) FROM os_data." + table
                        + " WHERE release_id = 'local' AND (" + invalidGeometry + ")")) {
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
        try (PreparedStatement statement = connection.prepareStatement(
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
                PreparedStatement statement = connection.prepareStatement(
                        "SELECT " + expression + " FROM os_data." + table
                                + " WHERE release_id = ?")) {
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
                ResultSet columns = statement.executeQuery(
                        "SELECT column_name FROM information_schema.columns "
                                + "WHERE table_schema = 'os_data' AND table_name = '" + table + "' "
                                + "ORDER BY ordinal_position")) {
            List<String> names = new ArrayList<>();
            while (columns.next()) {
                names.add(columns.getString(1));
            }
            assertTrue(names.contains("udprn"));
            assertTrue(names.contains("release_id"));

            String columnList = names.stream()
                    .map(DatabaseFeedIT::quoteIdentifier)
                    .reduce((left, right) -> left + ", " + right)
                    .orElseThrow();
            String selectList = names.stream()
                    .map(name -> switch (name) {
                        case "udprn" -> "\"udprn\" + 1000000000";
                        case "release_id" -> "'preserve-test'";
                        default -> quoteIdentifier(name);
                    })
                    .reduce((left, right) -> left + ", " + right)
                    .orElseThrow();
            try (Statement insert = connection.createStatement()) {
                assertEquals(1, insert.executeUpdate(
                        "INSERT INTO os_data." + table + " (" + columnList + ") "
                                + "SELECT " + selectList + " FROM os_data." + table
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
            List<Path> seeds = files
                    .filter(path -> path.getFileName().toString().endsWith(".sql"))
                    .toList();
            assertEquals(2, seeds.size(), "Expected one local repeatable seed per dataset");
            for (Path seed : seeds) {
                Path copy = target.resolve(seed.getFileName());
                Files.copy(seed, copy);
                Files.writeString(copy, "\n-- force repeatable seed execution\n",
                        StandardOpenOption.APPEND);
            }
        }
    }

    private static Connection connection() throws SQLException {
        return DriverManager.getConnection(
                POSTGRES.getJdbcUrl(), POSTGRES.getUsername(), POSTGRES.getPassword());
    }
}
