package uk.gov.companieshouse.addresslookup;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.Connection;
import java.sql.DriverManager;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;
import java.util.zip.ZipFile;
import liquibase.Contexts;
import liquibase.LabelExpression;
import liquibase.Liquibase;
import liquibase.changelog.ChangeLogParameters;
import liquibase.database.DatabaseFactory;
import liquibase.database.jvm.JdbcConnection;
import liquibase.parser.ChangeLogParserFactory;
import liquibase.resource.ClassLoaderResourceAccessor;
import liquibase.resource.ZipResourceAccessor;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.postgresql.PostgreSQLContainer;
import org.testcontainers.utility.DockerImageName;

/**
 * Exercises the ALS-13 feed, seed contexts, repeat runs and reverse SQL on
 * disposable PostGIS, without connecting to a developer or deployed database.
 * Also packages and applies the actual seed-free production ZIP.
 */
@Testcontainers
class LiquibaseFeedIT {
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
    void shouldIsolateSeedsRepeatWithoutReplayAndRollBackEveryChangeset() throws Exception {
        try (Connection connection = DriverManager.getConnection(
                POSTGRES.getJdbcUrl(), POSTGRES.getUsername(), POSTGRES.getPassword());
             var resources = new ClassLoaderResourceAccessor();
             var liquibase = new Liquibase("database/changelog-master.xml", resources,
                     DatabaseFactory.getInstance()
                             .findCorrectDatabaseImplementation(new JdbcConnection(connection)))) {
            liquibase.validate();
            liquibase.update(new Contexts(), new LabelExpression());
            for (String table : COUNTS.keySet()) {
                assertEquals(0, scalar(connection, "SELECT count(*) FROM os_data." + table), table);
            }
            assertEquals(0, scalar(connection,
                    "SELECT count(*) FROM databasechangelog WHERE id LIKE '2026-006:%'"));
            liquibase.update(new Contexts("local"), new LabelExpression());
            for (var entry : COUNTS.entrySet()) {
                String table = entry.getKey();
                assertEquals(entry.getValue().intValue(),
                        scalar(connection, "SELECT count(*) FROM os_data." + table), table);
                int srid = table.contains("_gb_") ? 27700 : 4258;
                assertEquals(0, scalar(connection, "SELECT count(*) FROM os_data." + table
                        + " WHERE geometry IS NULL OR ST_SRID(geometry) <> " + srid), table);
            }
            assertEquals(225, scalar(connection, "SELECT count(*) FROM public.royalmail_address_lookup"));
            int applied = scalar(connection, "SELECT count(*) FROM databasechangelog");
            assertEquals(42, applied);
            liquibase.update(new Contexts("local"), new LabelExpression());
            assertEquals(applied, scalar(connection, "SELECT count(*) FROM databasechangelog"));
            assertEquals(99, scalar(connection, "SELECT count(*) FROM os_data.add_gb_royalmailaddress_v1"));
            liquibase.rollback(applied, new Contexts("local"), new LabelExpression());
            assertEquals(0, scalar(connection, "SELECT count(*) FROM databasechangelog"));
            assertEquals(0, scalar(connection, """
                    SELECT count(*) FROM pg_namespace
                    WHERE nspname IN ('os_data', 'os_control', 'os_stage', 'address_lookup')
                    """));
            assertEquals(0, scalar(connection, """
                    SELECT count(*) FROM pg_class c JOIN pg_namespace n ON n.oid = c.relnamespace
                    WHERE n.nspname = 'public'
                    AND (c.relname LIKE 'add_%' OR c.relname = 'royalmail_address_lookup')
                    """));
            assertEquals(0, scalar(connection, """
                    SELECT count(*) FROM pg_proc p JOIN pg_namespace n ON n.oid = p.pronamespace
                    WHERE n.nspname = 'public' AND p.proname IN
                    ('set_royalmail_address_geometry', 'set_isl_royalmail_address_geometry')
                    """));
            assertTrue(scalar(connection, "SELECT count(*) FROM pg_extension WHERE extname = 'postgis'") > 0);
            liquibase.update(new Contexts("!local"), new LabelExpression());
            assertEquals(32, scalar(connection, "SELECT count(*) FROM databasechangelog"));
            assertEquals(0, scalar(connection, "SELECT count(*) FROM os_data.add_gb_royalmailaddress_v1"));
        }
    }

    @Test
    void shouldApplyTheActualProductionArchiveWithoutLocalChanges(@TempDir Path temporary) throws Exception {
        Path root = Path.of("..").toRealPath();
        Path archive = packageArchive(root, temporary);
        try (var zip = new ZipFile(archive.toFile())) {
            assertEquals(10, zip.size());
            Set<String> entries = zip.stream().map(entry -> entry.getName()).collect(Collectors.toSet());
            assertEquals(Set.of(
                    "database/changelog-master.xml", "database/version",
                    "database/production.properties", "database/staging.properties", "database/cidev.properties",
                    "database/changelogs/2026-001-create-address-tables.postgres.sql",
                    "database/changelogs/2026-002-create-os-control-schema.postgres.sql",
                    "database/changelogs/2026-003-create-os-data-schema-and-tables.postgres.sql",
                    "database/changelogs/2026-004-create-os-stage-schema-and-tables.postgres.sql",
                    "database/changelogs/2026-005-create-address-lookup-view.postgres.sql"), entries);
        }
        try (Connection admin = DriverManager.getConnection(
                POSTGRES.getJdbcUrl(), POSTGRES.getUsername(), POSTGRES.getPassword());
             var statement = admin.createStatement()) {
            statement.execute("CREATE DATABASE als13_archive");
        }
        String url = POSTGRES.getJdbcUrl().substring(0, POSTGRES.getJdbcUrl().lastIndexOf('/') + 1)
                + "als13_archive";
        try (Connection connection = DriverManager.getConnection(
                url, POSTGRES.getUsername(), POSTGRES.getPassword());
             var resources = new ZipResourceAccessor(archive);
             var liquibase = new Liquibase("database/changelog-master.xml", resources,
                     DatabaseFactory.getInstance()
                             .findCorrectDatabaseImplementation(new JdbcConnection(connection)))) {
            liquibase.validate();
            liquibase.update(new Contexts("local"), new LabelExpression());
            assertEquals(32, scalar(connection, "SELECT count(*) FROM databasechangelog"));
            for (String table : COUNTS.keySet()) {
                assertEquals(0, scalar(connection, "SELECT count(*) FROM os_data." + table), table);
            }
            assertEquals(0, scalar(connection, """
                    SELECT count(*) FROM databasechangelog
                    WHERE id LIKE '2026-006:%' OR id LIKE '2026-007:%'
                    OR id LIKE '2026-008:%' OR id LIKE '2026-009:%' OR id LIKE '2026-010:%'
                    """));
            liquibase.rollback(32, new Contexts("local"), new LabelExpression());
            assertEquals(0, scalar(connection, "SELECT count(*) FROM databasechangelog"));
        }
    }

    @Test
    void shouldIncludeLaterProductionChangesWithoutLocalArtifacts(@TempDir Path temporary) throws Exception {
        Path root = Path.of("..").toRealPath();
        Path fixture = temporary.resolve("source");
        Files.createDirectories(fixture);
        Files.copy(root.resolve("Makefile"), fixture.resolve("Makefile"));
        for (String directory : new String[]{"database", "database/changelogs"}) {
            Files.createDirectories(fixture.resolve(directory));
            try (var files = Files.list(root.resolve(directory))) {
                for (Path file : files.filter(Files::isRegularFile).toList()) {
                    Files.copy(file, fixture.resolve(directory).resolve(file.getFileName()));
                }
            }
        }
        String forward = "database/changelogs/2026-011-next-production-change.postgres.sql";
        Files.writeString(fixture.resolve(forward), """
                --liquibase formatted sql
                -- Jira: ALS-13
                -- Description: Synthetic forward-production packaging regression
                -- Author: team-atlas
                -- Created: 2026-10-12
                --changeset team-atlas:2026-011:01
                --comment: Verify later production groups are not silently dropped.
                CREATE TABLE public.forward_packaging_example (id integer PRIMARY KEY);
                --rollback DROP TABLE public.forward_packaging_example;
                """);
        Path archive = packageArchive(fixture, temporary);
        try (var zip = new ZipFile(archive.toFile());
             var resources = new ZipResourceAccessor(archive)) {
            assertNotNull(zip.getEntry(forward));
            assertEquals(11, zip.size());
            String master = "database/changelog-master.xml";
            var changelog = ChangeLogParserFactory.getInstance().getParser(master, resources)
                    .parse(master, new ChangeLogParameters(), resources);
            assertEquals(33, changelog.getChangeSets().size());
            assertTrue(changelog.getChangeSets().stream()
                    .anyMatch(change -> change.getId().equals("2026-011:01")));
            assertTrue(changelog.getChangeSets().stream()
                    .allMatch(change -> change.getContextFilter().isEmpty()));
        }
    }

    private Path packageArchive(Path root, Path output) throws Exception {
        Path artifact = output.resolve("feed");
        Path log = output.resolve("package.log");
        var packaging = new ProcessBuilder("make", "package-db-schema", "version=2.0.0",
                "db_schema_artifact=" + root.relativize(artifact))
                .directory(root.toFile()).redirectErrorStream(true).redirectOutput(log.toFile()).start();
        assertEquals(0, packaging.waitFor(), Files.readString(log));
        return output.resolve("feed-2.0.0.zip");
    }

    private int scalar(Connection connection, String sql) throws Exception {
        try (var statement = connection.createStatement(); var result = statement.executeQuery(sql)) {
            assertTrue(result.next(), sql);
            assertNotNull(result.getObject(1), sql);
            return result.getInt(1);
        }
    }
}
