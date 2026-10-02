package uk.gov.companieshouse.addresslookup.lambda.schema;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestInfo;
import org.junit.jupiter.api.io.TempDir;
import uk.gov.companieshouse.addresslookup.lambda.schema.MigrationResult.Status;
import uk.gov.companieshouse.addresslookup.lambda.schema.MigratorConfiguration.ArchiveLimits;

import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.LongSupplier;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Runs the migrator (archive extraction, Liquibase, every mode) against an in-memory H2 database in PostgreSQL mode,
 * so it needs no Docker. The changelog is a fixture with the same shape as the real one; the real, PostGIS-specific
 * changelog is checked by {@link ReleasedChangelogTest} and executed against Aurora by the pipeline's VALIDATE.
 * Each test gets a fresh database.
 */
class SchemaMigratorIT {

    private static final Path FIXTURE = Path.of("src/test/resources/h2-changelog");
    private static final DatabaseCredentials CREDENTIALS = new DatabaseCredentials("sa", "h2-in-memory");

    @TempDir
    Path work;

    private String jdbcUrl;

    @BeforeEach
    void freshDatabase(TestInfo test) throws Exception {
        jdbcUrl = "jdbc:h2:mem:" + test.getTestMethod().orElseThrow().getName()
                + ";MODE=PostgreSQL;DB_CLOSE_DELAY=-1";
        ChangelogArchive.extract(ChangelogPackaging.packageLikeMake(FIXTURE), work, ArchiveLimits.DEFAULT);
    }

    @Test
    void validatesWithoutChangingTheSchemaThenAppliesAndTagsTheRelease() throws Exception {
        SchemaMigrator migrator = migrator(Map.of("marker.label", "aws"));

        MigrationResult validated = migrator.migrate(request(MigrationMode.VALIDATE), work, CREDENTIALS, plenty());
        assertEquals(Status.VALIDATED, validated.status());
        assertEquals(List.of(
                "db/changelog/changes/creation/000-create-marker.sql::000-create-marker::address-lookup-api",
                "db/changelog/changes/creation/001-create-address.sql::001-create-address::address-lookup-api",
                "db/changelog/changes/creation/002-create-address-view.sql::002-create-address-view::address-lookup-api"),
                validated.pendingChangeSets(), "only the aws context's changesets, in order");
        assertTrue(validated.sql().contains("INSERT INTO marker (label) VALUES ('aws')"), validated.sql());
        assertNull(validated.postgisVersion());
        assertNull(query("SELECT table_name FROM information_schema.tables WHERE lower(table_name) = 'address'"),
                "VALIDATE must not change the schema");

        MigrationResult updated = migrator.migrate(request(MigrationMode.UPDATE), work, CREDENTIALS, plenty());
        assertEquals(Status.COMPLETE, updated.status());
        assertEquals(validated.pendingChangeSets(), updated.appliedChangeSets());
        assertEquals("db-schema-1.0.0", updated.tag());
        assertEquals("aws", query("SELECT label FROM marker"));
        assertEquals("0", query("SELECT count(*) FROM address"), "the local seed must not run");

        MigrationResult status = migrator.migrate(request(MigrationMode.STATUS), work, CREDENTIALS, plenty());
        assertEquals(Status.UP_TO_DATE, status.status());
        assertEquals("db-schema-1.0.0", status.tag());
    }

    @Test
    void marksAChangesetRanWhenItsPreconditionFails() throws Exception {
        execute("CREATE TABLE marker (label varchar(20) NOT NULL)");

        MigrationResult updated = migrator(Map.of()).migrate(request(MigrationMode.UPDATE), work, CREDENTIALS, plenty());

        assertEquals(Status.COMPLETE, updated.status());
        assertEquals("MARK_RAN", query("SELECT exectype FROM databasechangelog WHERE id = '000-create-marker'"));
        assertNull(query("SELECT label FROM marker"));
    }

    @Test
    void returnsPartialWhenTimeRunsOutAndResumes() throws Exception {
        SchemaMigrator migrator = migrator(Map.of());
        AtomicInteger calls = new AtomicInteger();

        MigrationResult partial = migrator.migrate(request(MigrationMode.UPDATE), work, CREDENTIALS,
                () -> calls.incrementAndGet() <= 2 ? 600_000 : 1_000);

        assertEquals(Status.PARTIAL, partial.status());
        assertEquals(2, partial.appliedChangeSets().size());
        assertFalse(partial.pendingChangeSets().isEmpty());
        assertNull(partial.tag());
        assertEquals("0", query("SELECT count(*) FROM databasechangeloglock WHERE locked"));

        MigrationResult resumed = migrator.migrate(request(MigrationMode.UPDATE), work, CREDENTIALS, plenty());
        assertEquals(Status.COMPLETE, resumed.status());
        assertEquals(partial.pendingChangeSets(), resumed.appliedChangeSets());
    }

    @Test
    void aReleaseWithNoChangesKeepsThePreviousTag() throws Exception {
        SchemaMigrator migrator = migrator(Map.of());
        migrator.migrate(request(MigrationMode.UPDATE), work, CREDENTIALS, plenty());

        MigrationResult next = migrator.migrate(new MigrationRequest(MigrationMode.UPDATE, "1.0.1", "0".repeat(64)),
                work, CREDENTIALS, plenty());

        assertEquals(Status.COMPLETE, next.status());
        assertTrue(next.appliedChangeSets().isEmpty());
        assertEquals("db-schema-1.0.0", next.tag());
    }

    @Test
    void refusesAChangelogWhoseAppliedChangesetWasEdited() throws Exception {
        SchemaMigrator migrator = migrator(Map.of());
        migrator.migrate(request(MigrationMode.UPDATE), work, CREDENTIALS, plenty());

        Path view = work.resolve("db/changelog/changes/creation/002-create-address-view.sql");
        Files.writeString(view, Files.readString(view).replace("'GB' AS address_source", "'UK' AS address_source"));

        assertThrows(Exception.class,
                () -> migrator.migrate(request(MigrationMode.VALIDATE), work, CREDENTIALS, plenty()));
    }

    @Test
    void releasesAStaleLock() throws Exception {
        SchemaMigrator migrator = migrator(Map.of());
        migrator.migrate(request(MigrationMode.STATUS), work, CREDENTIALS, plenty());
        execute("UPDATE databasechangeloglock SET locked = true, lockgranted = now(), lockedby = 'killed'");

        MigrationResult released = migrator.migrate(request(MigrationMode.RELEASE_LOCKS), work, CREDENTIALS, plenty());

        assertEquals(Status.LOCKS_RELEASED, released.status());
        assertEquals("0", query("SELECT count(*) FROM databasechangeloglock WHERE locked"));
    }

    private SchemaMigrator migrator(Map<String, String> changeLogParameters) {
        MigratorConfiguration config = new MigratorConfiguration("bucket", "address-lookup-api", jdbcUrl,
                "/u", "/p", "aws", "", changeLogParameters, 120_000, 60_000, ArchiveLimits.DEFAULT);
        return new SchemaMigrator(config,
                credentials -> DriverManager.getConnection(jdbcUrl, credentials.username(), credentials.password()));
    }

    private static MigrationRequest request(MigrationMode mode) {
        return new MigrationRequest(mode, "1.0.0", "0".repeat(64));
    }

    private static LongSupplier plenty() {
        return () -> 900_000;
    }

    private String query(String sql) throws SQLException {
        try (Connection connection = DriverManager.getConnection(jdbcUrl, CREDENTIALS.username(), CREDENTIALS.password());
             Statement statement = connection.createStatement();
             ResultSet rows = statement.executeQuery(sql)) {
            return rows.next() ? rows.getString(1) : null;
        }
    }

    private void execute(String sql) throws SQLException {
        try (Connection connection = DriverManager.getConnection(jdbcUrl, CREDENTIALS.username(), CREDENTIALS.password());
             Statement statement = connection.createStatement()) {
            statement.execute(sql);
        }
    }
}
