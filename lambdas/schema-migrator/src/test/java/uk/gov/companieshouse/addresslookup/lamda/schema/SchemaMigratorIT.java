package uk.gov.companieshouse.addresslookup.lamda.schema;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestInfo;
import org.junit.jupiter.api.io.TempDir;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.postgresql.PostgreSQLContainer;
import org.testcontainers.utility.DockerImageName;
import uk.gov.companieshouse.addresslookup.lamda.schema.MigrationResult.Status;
import uk.gov.companieshouse.addresslookup.lamda.schema.MigratorConfiguration.ArchiveLimits;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.stream.Stream;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Runs the real db-schema changelog, packaged exactly as {@code make package-db-schema} packages it, against the
 * PostGIS image the service's own tests use. Each test gets a fresh database.
 */
@Testcontainers
class SchemaMigratorIT {

    private static final Path CHANGELOG_SOURCE = Path.of("../../db-schema/src/main/resources");

    @Container
    @SuppressWarnings("resource")
    private static final PostgreSQLContainer POSTGRES =
            new PostgreSQLContainer(DockerImageName.parse("postgis/postgis:18-3.6")
                    .asCompatibleSubstituteFor("postgres"));

    @TempDir
    Path work;

    private String jdbcUrl;
    private DatabaseCredentials credentials;
    private Path changelogs;
    private String imagePostgisVersion;

    @BeforeEach
    void freshDatabase(TestInfo test) throws Exception {
        String database = "t_" + test.getTestMethod().orElseThrow().getName().toLowerCase();
        try (Connection admin = DriverManager.getConnection(POSTGRES.getJdbcUrl(), POSTGRES.getUsername(),
                POSTGRES.getPassword()); Statement sql = admin.createStatement()) {
            sql.execute("DROP DATABASE IF EXISTS " + database);
            sql.execute("CREATE DATABASE " + database);
            try (ResultSet rows = sql.executeQuery(
                    "SELECT default_version FROM pg_available_extensions WHERE name = 'postgis'")) {
                rows.next();
                imagePostgisVersion = rows.getString(1);
            }
        }
        jdbcUrl = POSTGRES.getJdbcUrl().replaceFirst("/[^/?]+(\\?|$)", "/" + database + "$1");
        credentials = new DatabaseCredentials(POSTGRES.getUsername(), POSTGRES.getPassword());

        ChangelogArchive.extract(packageLikeMake(), work, ArchiveLimits.DEFAULT);
        changelogs = work;
    }

    @Test
    void createsPostgisWhenAbsentThenAppliesTheSchemaAndTagsTheRelease() throws Exception {
        // The image only installs its latest patch release; Aurora 18.3 gets the changelog's pinned 3.6.1.
        SchemaMigrator migrator = migrator(Map.of("postgis.version", imagePostgisVersion));

        MigrationResult validated = migrator.migrate(request(MigrationMode.VALIDATE), changelogs, credentials, plenty());
        assertEquals(Status.VALIDATED, validated.status());
        assertNull(validated.postgisVersion());
        assertTrue(validated.pendingChangeSets().get(0).contains("000-create-postgis-extension"));
        assertTrue(validated.sql().contains("CREATE EXTENSION IF NOT EXISTS postgis WITH SCHEMA public VERSION '"
                + imagePostgisVersion + "'"));
        assertNull(query("SELECT to_regclass('address_lookup.address_lookup')::text"), "VALIDATE must not change the schema");

        MigrationResult updated = migrator.migrate(request(MigrationMode.UPDATE), changelogs, credentials, plenty());
        assertEquals(Status.COMPLETE, updated.status());
        assertEquals(validated.pendingChangeSets(), updated.appliedChangeSets());
        assertEquals(imagePostgisVersion, updated.postgisVersion());
        assertEquals("db-schema-1.0.0", updated.tag());
        assertEquals("EXECUTED", query("SELECT exectype FROM databasechangelog WHERE id = '000-create-postgis-extension'"));
        assertNotNull(query("SELECT to_regclass('address_lookup.address_lookup')::text"));

        MigrationResult status = migrator.migrate(request(MigrationMode.STATUS), changelogs, credentials, plenty());
        assertEquals(Status.UP_TO_DATE, status.status());
        assertEquals("db-schema-1.0.0", status.tag());
    }

    @Test
    void leavesAnExistingPostgisInstallationAlone() throws Exception {
        execute("CREATE EXTENSION postgis");

        // Default parameters: the pinned 3.6.1, which this image cannot install. The precondition must stop the
        // changeset from ever trying.
        MigrationResult updated = migrator(Map.of())
                .migrate(request(MigrationMode.UPDATE), changelogs, credentials, plenty());

        assertEquals(Status.COMPLETE, updated.status());
        assertEquals(imagePostgisVersion, updated.postgisVersion());
        assertEquals("MARK_RAN", query("SELECT exectype FROM databasechangelog WHERE id = '000-create-postgis-extension'"));
    }

    @Test
    void neverSilentlyInstallsADifferentPostgisVersion() {
        // Without PostGIS, and with 3.6.1 unavailable, the migration must fail rather than fall back to 001's
        // unversioned CREATE EXTENSION.
        assertThrows(Exception.class, () -> migrator(Map.of())
                .migrate(request(MigrationMode.UPDATE), changelogs, credentials, plenty()));
    }

    @Test
    void returnsPartialWhenTimeRunsOutAndResumes() throws Exception {
        SchemaMigrator migrator = migrator(Map.of("postgis.version", imagePostgisVersion));
        AtomicInteger calls = new AtomicInteger();

        MigrationResult partial = migrator.migrate(request(MigrationMode.UPDATE), changelogs, credentials,
                () -> calls.incrementAndGet() <= 2 ? 600_000 : 1_000);

        assertEquals(Status.PARTIAL, partial.status());
        assertEquals(2, partial.appliedChangeSets().size());
        assertFalse(partial.pendingChangeSets().isEmpty());
        assertNull(partial.tag());
        assertEquals("0", query("SELECT count(*)::text FROM databasechangeloglock WHERE locked"));

        MigrationResult resumed = migrator.migrate(request(MigrationMode.UPDATE), changelogs, credentials, plenty());
        assertEquals(Status.COMPLETE, resumed.status());
        assertEquals(partial.pendingChangeSets(), resumed.appliedChangeSets());
    }

    @Test
    void aReleaseWithNoChangesKeepsThePreviousTag() throws Exception {
        SchemaMigrator migrator = migrator(Map.of("postgis.version", imagePostgisVersion));
        migrator.migrate(request(MigrationMode.UPDATE), changelogs, credentials, plenty());

        MigrationResult next = migrator.migrate(new MigrationRequest(MigrationMode.UPDATE, "1.0.1", "0".repeat(64)),
                changelogs, credentials, plenty());

        assertEquals(Status.COMPLETE, next.status());
        assertTrue(next.appliedChangeSets().isEmpty());
        assertEquals("db-schema-1.0.0", next.tag());
    }

    @Test
    void refusesAChangelogWhoseAppliedChangesetWasEdited() throws Exception {
        SchemaMigrator migrator = migrator(Map.of("postgis.version", imagePostgisVersion));
        migrator.migrate(request(MigrationMode.UPDATE), changelogs, credentials, plenty());

        Path view = changelogs.resolve("db/changelog/changes/creation/005-create-address-lookup-view.sql");
        Files.writeString(view, Files.readString(view).replace("'GB' AS address_source", "'UK' AS address_source"));

        assertThrows(Exception.class,
                () -> migrator.migrate(request(MigrationMode.VALIDATE), changelogs, credentials, plenty()));
    }

    @Test
    void releasesAStaleLock() throws Exception {
        SchemaMigrator migrator = migrator(Map.of("postgis.version", imagePostgisVersion));
        migrator.migrate(request(MigrationMode.STATUS), changelogs, credentials, plenty());
        execute("UPDATE databasechangeloglock SET locked = true, lockgranted = now(), lockedby = 'killed'");

        MigrationResult released = migrator.migrate(request(MigrationMode.RELEASE_LOCKS), changelogs, credentials, plenty());

        assertEquals(Status.LOCKS_RELEASED, released.status());
        assertEquals("0", query("SELECT count(*)::text FROM databasechangeloglock WHERE locked"));
    }

    private SchemaMigrator migrator(Map<String, String> changeLogParameters) {
        return new SchemaMigrator(new MigratorConfiguration("bucket", "address-lookup-api", jdbcUrl,
                "/u", "/p", "aws", "", changeLogParameters, 120_000, 60_000, ArchiveLimits.DEFAULT));
    }

    private static MigrationRequest request(MigrationMode mode) {
        return new MigrationRequest(mode, "1.0.0", "0".repeat(64));
    }

    private static java.util.function.LongSupplier plenty() {
        return () -> 900_000;
    }

    private String query(String sql) throws SQLException {
        try (Connection connection = DriverManager.getConnection(jdbcUrl, credentials.username(), credentials.password());
             Statement statement = connection.createStatement();
             ResultSet rows = statement.executeQuery(sql)) {
            return rows.next() ? rows.getString(1) : null;
        }
    }

    private void execute(String sql) throws SQLException {
        try (Connection connection = DriverManager.getConnection(jdbcUrl, credentials.username(), credentials.password());
             Statement statement = connection.createStatement()) {
            statement.execute(sql);
        }
    }

    /** Mirrors the Makefile's package-db-schema: the master changelog and changes/creation, never seeds or data. */
    private static byte[] packageLikeMake() throws IOException {
        ByteArrayOutputStream bytes = new ByteArrayOutputStream();
        try (ZipOutputStream zip = new ZipOutputStream(bytes);
             Stream<Path> files = Files.walk(CHANGELOG_SOURCE.resolve("db/changelog"))) {
            for (Path file : files.filter(Files::isRegularFile).sorted().toList()) {
                String name = CHANGELOG_SOURCE.relativize(file).toString().replace('\\', '/');
                if (name.equals(MigratorConfiguration.MASTER_CHANGELOG) || name.startsWith("db/changelog/changes/creation/")) {
                    zip.putNextEntry(new ZipEntry(name));
                    zip.write(Files.readAllBytes(file));
                    zip.closeEntry();
                }
            }
        }
        return bytes.toByteArray();
    }
}
