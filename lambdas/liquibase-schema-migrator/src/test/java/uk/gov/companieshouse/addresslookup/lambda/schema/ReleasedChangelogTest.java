package uk.gov.companieshouse.addresslookup.lambda.schema;

import liquibase.Contexts;
import liquibase.Liquibase;
import liquibase.changelog.ChangeSet;
import liquibase.changelog.filter.ContextChangeSetFilter;
import liquibase.change.Change;
import liquibase.database.Database;
import liquibase.database.DatabaseFactory;
import liquibase.database.OfflineConnection;
import liquibase.resource.DirectoryResourceAccessor;
import liquibase.sql.Sql;
import liquibase.sqlgenerator.SqlGeneratorFactory;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import uk.gov.companieshouse.addresslookup.lambda.schema.MigratorConfiguration.ArchiveLimits;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.stream.Stream;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Checks the real changelog, packaged as {@code make package-db-schema} packages it, with Liquibase's offline
 * PostgreSQL mode: it must parse, validate and render the SQL the Lambda would run in the aws context. No database
 * or Docker is needed; the SQL itself is executed against Aurora by the pipeline's VALIDATE and UPDATE.
 */
class ReleasedChangelogTest {

    private static final Path CHANGELOG_SOURCE = Path.of("../../address-lookup-api/src/main/resources");

    @TempDir
    Path work;

    @BeforeEach
    void extractTheReleasedArchive() throws Exception {
        ChangelogArchive.extract(ChangelogPackaging.packageLikeMake(CHANGELOG_SOURCE), work, ArchiveLimits.DEFAULT);
    }

    @Test
    void shipsOnlyTheMasterChangelogAndCreationScripts() throws Exception {
        try (Stream<Path> files = Files.walk(work)) {
            files.filter(Files::isRegularFile)
                    .map(file -> work.relativize(file).toString().replace('\\', '/'))
                    .forEach(name -> assertTrue(name.equals(MigratorConfiguration.MASTER_CHANGELOG)
                            || name.startsWith("db/changelog/changes/creation/"), name));
        }
    }

    @Test
    void rendersTheAwsMigrationWithThePinnedPostgisVersion() throws Exception {
        String sql = updateSql("aws");

        assertTrue(sql.contains("CREATE EXTENSION IF NOT EXISTS postgis WITH SCHEMA public VERSION '3.6.1'"), sql);
        assertTrue(sql.indexOf("VERSION '3.6.1'") < sql.indexOf("CREATE TABLE add_gb_royalmailaddress"),
                "PostGIS must be created before anything that uses it");
        assertTrue(sql.contains("address_lookup"), "the address_lookup view must be created");
        assertFalse(sql.contains("${"), "every changelog property must be substituted");
    }

    @Test
    void theLocalContextSkipsThePinnedPostgisChangeset() throws Exception {
        assertFalse(updateSql("local").contains("VERSION '3.6.1'"));
    }

    @Test
    void everyChangesetHasAUniqueIdentity() throws Exception {
        try (Liquibase liquibase = liquibase()) {
            var changeSets = liquibase.getDatabaseChangeLog().getChangeSets();
            assertEquals(changeSets.size(),
                    changeSets.stream().map(changeSet -> changeSet.getAuthor() + ":" + changeSet.getId()).distinct().count());
        }
    }

    /** The SQL of every changeset the given contexts select, in changelog order, as the Lambda would run it. */
    private String updateSql(String contexts) throws Exception {
        try (Liquibase liquibase = liquibase()) {
            liquibase.validate();
            ContextChangeSetFilter filter = new ContextChangeSetFilter(new Contexts(contexts));
            StringBuilder sql = new StringBuilder();
            for (ChangeSet changeSet : liquibase.getDatabaseChangeLog().getChangeSets()) {
                if (!filter.accepts(changeSet).isAccepted()) {
                    continue;
                }
                for (Change change : changeSet.getChanges()) {
                    for (Sql statement : SqlGeneratorFactory.getInstance()
                            .generateSql(change.generateStatements(liquibase.getDatabase()), liquibase.getDatabase())) {
                        sql.append(statement.toSql()).append(";\n");
                    }
                }
            }
            return sql.toString();
        }
    }

    private Liquibase liquibase() throws Exception {
        DirectoryResourceAccessor resources = new DirectoryResourceAccessor(work);
        Database database = DatabaseFactory.getInstance().findCorrectDatabaseImplementation(
                new OfflineConnection("offline:postgresql?version=18.3&changeLogFile="
                        + work.resolve("databasechangelog.csv"), resources));
        return new Liquibase(MigratorConfiguration.MASTER_CHANGELOG, resources, database);
    }
}
