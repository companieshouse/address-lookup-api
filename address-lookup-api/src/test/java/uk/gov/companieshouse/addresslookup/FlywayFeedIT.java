package uk.gov.companieshouse.addresslookup;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.sql.Connection;
import java.sql.DriverManager;
import java.util.HexFormat;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.stream.Collectors;
import java.util.zip.ZipFile;
import org.flywaydb.core.Flyway;
import org.flywaydb.core.api.FlywayException;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.postgresql.PostgreSQLContainer;
import org.testcontainers.utility.DockerImageName;

/**
 * Exercises fresh Flyway initialization, local fixture isolation and production
 * packaging against disposable PostGIS only.
 */
@Testcontainers
class FlywayFeedIT {
    private static final Map<String, Integer> COUNTS = Map.of(
            "add_gb_royalmailaddress_v1", 99,
            "add_gb_builtaddress_v3", 794,
            "add_isl_royalmailaddress_v1", 126,
            "add_isl_builtaddress_v3", 111);

    @Container
    private static final PostgreSQLContainer POSTGRES = new PostgreSQLContainer(
            DockerImageName.parse("postgis/postgis:18-3.6")
                    .asCompatibleSubstituteFor("postgres"))
            .withCreateContainerCmdModifier(command -> command.withPlatform("linux/amd64"));

    @Test
    void shouldSeparateSchemaAndLocalRunsAndMakeRepeatedMigrationANoop() throws Exception {
        try (Connection connection = connection(POSTGRES.getJdbcUrl())) {
            Flyway schemaOnly = flyway(POSTGRES.getJdbcUrl(), "classpath:db/migration");
            assertEquals(5, schemaOnly.migrate().migrationsExecuted);
            assertEquals(5, scalar(connection, "SELECT count(*) FROM public.flyway_schema_history"));
            assertEquals(0, scalar(connection, "SELECT count(*) FROM os_data.add_gb_royalmailaddress_v1"));
            assertEquals(0, scalar(connection, "SELECT count(*) FROM os_data.add_gb_builtaddress_v3"));

            Flyway local = flyway(POSTGRES.getJdbcUrl(),
                    "classpath:db/migration", "classpath:db/local-migration");
            assertEquals(5, local.migrate().migrationsExecuted);
            for (var entry : COUNTS.entrySet()) {
                String table = entry.getKey();
                assertEquals(entry.getValue().intValue(),
                        scalar(connection, "SELECT count(*) FROM os_data." + table), table);
                int srid = table.contains("_gb_") ? 27700 : 4258;
                assertEquals(0, scalar(connection, "SELECT count(*) FROM os_data." + table
                        + " WHERE geometry IS NULL OR ST_SRID(geometry) <> " + srid), table);
            }
            assertEquals(225, scalar(connection, "SELECT count(*) FROM public.royalmail_address_lookup"));
            assertEquals(10, scalar(connection, "SELECT count(*) FROM public.flyway_schema_history"));
            assertEquals(0, local.migrate().migrationsExecuted);
            assertEquals(99, scalar(connection, "SELECT count(*) FROM os_data.add_gb_royalmailaddress_v1"));
            assertEquals(0, scalar(connection, """
                    SELECT count(*) FROM os_data.add_gb_builtaddress_v3
                    WHERE uprn = 200000530482 AND organisationname IS NOT NULL
                    """));
            assertTrue(scalar(connection,
                    "SELECT count(*) FROM pg_extension WHERE extname = 'postgis'") > 0);
        }
    }

    @Test
    void shouldPackageAndApplyOnlyProductionMigrations(@TempDir Path temporary) throws Exception {
        Path root = Path.of("..").toRealPath();
        Path archive = packageArchive(root, temporary);
        Set<String> entries;
        try (var zip = new ZipFile(archive.toFile())) {
            entries = zip.stream().map(entry -> entry.getName()).collect(Collectors.toSet());
            assertEquals(11, entries.size());
            assertTrue(entries.contains("database/version"));
            assertTrue(entries.contains("database/MANIFEST.sha256"));
            assertTrue(entries.contains("database/config/flyway/common.conf"));
            assertTrue(entries.contains("database/config/flyway/production.conf"));
            assertTrue(entries.contains("database/config/flyway/staging.conf"));
            assertTrue(entries.contains("database/config/flyway/cidev.conf"));
            assertEquals(5, entries.stream().filter(name -> name.startsWith("database/migrations/schema/")).count());
            assertFalse(entries.stream().anyMatch(name -> name.contains("/local/")));
            assertFalse(entries.stream().anyMatch(name -> name.contains("test-data")));
            assertFalse(entries.stream().anyMatch(name -> name.endsWith("local.conf")));
            assertFalse(entries.stream().anyMatch(name -> name.contains("spring/local")));
            assertManifestMatches(zip);
        }

        Path extracted = temporary.resolve("extracted");
        try (var zip = new ZipFile(archive.toFile())) {
            var files = zip.entries();
            while (files.hasMoreElements()) {
                var entry = files.nextElement();
                Path destination = extracted.resolve(entry.getName());
                if (entry.isDirectory()) {
                    Files.createDirectories(destination);
                } else {
                    Files.createDirectories(destination.getParent());
                    try (var input = zip.getInputStream(entry)) {
                        Files.copy(input, destination);
                    }
                }
            }
        }
        createDatabase("als13_archive");
        String archiveUrl = databaseUrl("als13_archive");
        try (Connection connection = connection(archiveUrl)) {
            Flyway production = flyway(archiveUrl,
                    "filesystem:" + extracted.resolve("database/migrations/schema"));
            assertEquals(5, production.migrate().migrationsExecuted);
            assertEquals(5, scalar(connection, "SELECT count(*) FROM public.flyway_schema_history"));
            assertEquals(0, scalar(connection, "SELECT count(*) FROM os_data.add_gb_royalmailaddress_v1"));
            assertEquals(0, scalar(connection, "SELECT count(*) FROM os_data.add_gb_builtaddress_v3"));
        }
    }

    @Test
    void shouldPackageLaterSchemaVersionsAndRejectDuplicateVersions(@TempDir Path temporary)
            throws Exception {
        Path root = Path.of("..").toRealPath();
        Path fixture = temporary.resolve("fixture");
        copyProductionFeed(root, fixture);
        Path future = fixture.resolve("database/migrations/schema/V2026.011__future_schema_change.sql");
        Files.writeString(future, "-- Jira: ALS-13\nCREATE TABLE public.future_change (id integer);\n");
        Path archive = temporary.resolve("future.zip");
        assertTrue(runPackager(fixture, archive));
        try (var zip = new ZipFile(archive.toFile())) {
            assertTrue(zip.getEntry("database/migrations/schema/V2026.011__future_schema_change.sql") != null);
            assertEquals(12, zip.size());
            String manifest;
            try (var input = zip.getInputStream(zip.getEntry("database/MANIFEST.sha256"))) {
                manifest = new String(input.readAllBytes(), java.nio.charset.StandardCharsets.UTF_8);
            }
            assertTrue(manifest.contains("database/migrations/schema/V2026.011__future_schema_change.sql"));
            assertManifestMatches(zip);
        }

        Files.writeString(fixture.resolve("database/migrations/schema/V2026.1__duplicate_version.sql"),
                "SELECT 1;\n");
        Path duplicate = temporary.resolve("duplicate.zip");
        assertFalse(runPackager(fixture, duplicate));
    }

    @Test
    void shouldRejectMalformedAndDuplicateMigrationsAndDetectChecksumChanges(@TempDir Path temporary)
            throws Exception {
        String schema = "als13_test_" + UUID.randomUUID().toString().replace("-", "");
        Path malformed = Files.createDirectories(temporary.resolve("malformed"));
        Files.writeString(malformed.resolve("not-a-versioned-migration.sql"), "SELECT 1;\n");
        Flyway malformedFlyway = customFlyway(schema, malformed);
        assertThrows(FlywayException.class, malformedFlyway::migrate);

        Path first = Files.createDirectories(temporary.resolve("first"));
        Path second = Files.createDirectories(temporary.resolve("second"));
        Files.writeString(first.resolve("V1__first.sql"), "SELECT 1;\n");
        Files.writeString(second.resolve("V1__second.sql"), "SELECT 2;\n");
        Flyway duplicateFlyway = Flyway.configure()
                .dataSource(POSTGRES.getJdbcUrl(), POSTGRES.getUsername(), POSTGRES.getPassword())
                .locations("filesystem:" + first, "filesystem:" + second)
                .schemas(schema + "_duplicate").defaultSchema(schema + "_duplicate")
                .validateMigrationNaming(true).cleanDisabled(true).load();
        assertThrows(FlywayException.class, duplicateFlyway::migrate);

        Path checksum = Files.createDirectories(temporary.resolve("checksum"));
        Path migration = checksum.resolve("V1__initial.sql");
        Files.writeString(migration, "CREATE TABLE checksum_test (id integer);\n");
        Flyway checksumFlyway = customFlyway(schema + "_checksum", checksum);
        checksumFlyway.migrate();
        Files.writeString(migration, "CREATE TABLE checksum_test (id integer, value text);\n");
        assertThrows(FlywayException.class, checksumFlyway::validate);
    }

    private Flyway flyway(String url, String... locations) {
        return Flyway.configure()
                .dataSource(url, POSTGRES.getUsername(), POSTGRES.getPassword())
                .locations(locations)
                .defaultSchema("public")
                .schemas("public")
                .validateMigrationNaming(true)
                .validateOnMigrate(true)
                .baselineOnMigrate(false)
                .cleanDisabled(true)
                .load();
    }

    private Flyway customFlyway(String schema, Path location) {
        return Flyway.configure()
                .dataSource(POSTGRES.getJdbcUrl(), POSTGRES.getUsername(), POSTGRES.getPassword())
                .locations("filesystem:" + location)
                .schemas(schema)
                .defaultSchema(schema)
                .validateMigrationNaming(true)
                .validateOnMigrate(true)
                .baselineOnMigrate(false)
                .cleanDisabled(true)
                .load();
    }

    private Connection connection(String url) throws Exception {
        return DriverManager.getConnection(url, POSTGRES.getUsername(), POSTGRES.getPassword());
    }

    private int scalar(Connection connection, String sql) throws Exception {
        try (var statement = connection.createStatement(); var result = statement.executeQuery(sql)) {
            assertTrue(result.next(), sql);
            return result.getInt(1);
        }
    }

    private void createDatabase(String database) throws Exception {
        try (Connection admin = connection(POSTGRES.getJdbcUrl());
             var statement = admin.createStatement()) {
            statement.execute("CREATE DATABASE " + database);
        }
    }

    private String databaseUrl(String database) {
        return POSTGRES.getJdbcUrl().substring(0, POSTGRES.getJdbcUrl().lastIndexOf('/') + 1) + database;
    }

    private Path packageArchive(Path root, Path outputDirectory) throws Exception {
        Path relative = root.relativize(outputDirectory.resolve("address-lookup-db-schema"));
        Path archive = root.resolve(relative + "-2.0.0.zip").normalize();
        Path log = outputDirectory.resolve("package.log");
        var process = new ProcessBuilder("make", "package-db-schema", "version=2.0.0",
                "db_schema_artifact=" + relative)
                .directory(root.toFile()).redirectErrorStream(true).redirectOutput(log.toFile()).start();
        assertEquals(0, process.waitFor(), Files.readString(log));
        return archive;
    }

    private void copyProductionFeed(Path source, Path destination) throws Exception {
        Files.createDirectories(destination.resolve("database/config/flyway"));
        Files.createDirectories(destination.resolve("database/migrations/schema"));
        Files.createDirectories(destination.resolve("database/migrations/local"));
        Files.copy(source.resolve("database/version"), destination.resolve("database/version"));
        try (var configs = Files.list(source.resolve("database/config/flyway"))) {
            for (Path config : configs.toList()) {
                Files.copy(config, destination.resolve("database/config/flyway").resolve(config.getFileName()));
            }
        }
        try (var migrations = Files.list(source.resolve("database/migrations/schema"))) {
            for (Path migration : migrations.toList()) {
                Files.copy(migration,
                        destination.resolve("database/migrations/schema").resolve(migration.getFileName()));
            }
        }
    }

    private boolean runPackager(Path root, Path archive) throws Exception {
        Path log = archive.resolveSibling(archive.getFileName() + ".log");
        var process = new ProcessBuilder("python3",
                Path.of("..").toRealPath().resolve("database/tools/package_db_schema.py").toString(),
                "--version", "2.0.0", "--output", archive.toString(), "--project-root", root.toString())
                .redirectErrorStream(true).redirectOutput(log.toFile()).start();
        int result = process.waitFor();
        if (result == 0) {
            return true;
        }
        assertFalse(Files.readString(log).isBlank());
        return false;
    }

    private void assertManifestMatches(ZipFile zip) throws Exception {
        try (var input = zip.getInputStream(zip.getEntry("database/MANIFEST.sha256"))) {
            String manifest = new String(input.readAllBytes(), java.nio.charset.StandardCharsets.UTF_8);
            for (String line : manifest.lines().toList()) {
                String[] entry = line.split("  ", 2);
                assertEquals(2, entry.length, line);
                var file = zip.getEntry(entry[1]);
                assertNotNull(file, entry[1]);
                try (var content = zip.getInputStream(file)) {
                    String digest = HexFormat.of().formatHex(
                            MessageDigest.getInstance("SHA-256").digest(content.readAllBytes()));
                    assertEquals(entry[0], digest, entry[1]);
                }
            }
        }
    }
}
