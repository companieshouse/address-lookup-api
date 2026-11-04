package uk.gov.companieshouse.addresslookup.dbbootstrap;

import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.Test;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.postgresql.PostgreSQLContainer;
import org.testcontainers.utility.DockerImageName;

import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.ResultSet;
import java.sql.Statement;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

@Testcontainers
class DatabaseBootstrapIT {

    @SuppressWarnings("resource")
    @Container
    private static final PostgreSQLContainer POSTGRES = new PostgreSQLContainer(
            DockerImageName.parse("postgis/postgis:18-3.6").asCompatibleSubstituteFor("postgres"))
            .withUsername("bootstrap_master")
            .withCreateContainerCmdModifier(command -> command.withPlatform("linux/amd64"));

    @Test
    void bootstrapsThenMigratesAllFourVersionsAsTheNonMasterOwner() throws Exception {
        DatabaseCredentials master = createDatabase("fresh_bootstrap");
        DatabaseCredentials owner = owner(master);
        BootstrapVerifier verifier = new BootstrapVerifier(null);

        try (Connection connection = connect(master)) {
            DatabaseBootstrap.bootstrap(connection, master.username(), owner);
            assertEquals(BootstrapVerifier.DatabaseState.EMPTY,
                    verifier.verify(connection, master, owner.username()).databaseState());
            DatabaseBootstrap.bootstrap(connection, master.username(), owner);
            assertEquals(BootstrapVerifier.DatabaseState.EMPTY,
                    verifier.verify(connection, master, owner.username()).databaseState());
        }

        Flyway flyway = Flyway.configure()
                .dataSource(owner.jdbcUrl(), owner.username(), owner.password())
                .locations("filesystem:" + migrations())
                .defaultSchema("public")
                .baselineOnMigrate(false)
                .createSchemas(false)
                .load();
        assertEquals(4, flyway.migrate().migrationsExecuted);
        flyway.validate();
        assertEquals(List.of("2026.001", "2026.002", "2026.003", "2026.004"),
                List.of(flyway.info().applied()).stream()
                        .map(migration -> migration.getVersion().getVersion()).toList());
        assertEquals(0, flyway.migrate().migrationsExecuted);

        try (Connection connection = connect(owner); Statement statement = connection.createStatement()) {
            try (ResultSet result = statement.executeQuery(
                    "SELECT current_user, rolsuper FROM pg_roles WHERE rolname = current_user")) {
                assertTrue(result.next());
                assertEquals(owner.username(), result.getString(1));
                assertFalse(result.getBoolean(2));
            }
            try (ResultSet result = statement.executeQuery("""
                    SELECT count(*) FROM pg_namespace
                    WHERE nspname IN ('os_control', 'os_stage', 'os_data', 'os_prev', 'os_ingest', 'address_lookup')
                      AND nspowner = (SELECT oid FROM pg_roles WHERE rolname = current_user)
                    """)) {
                assertTrue(result.next());
                assertEquals(6, result.getInt(1));
            }
        }
        try (Connection connection = connect(master)) {
            DatabaseBootstrap.bootstrap(connection, master.username(), owner);
            assertEquals(BootstrapVerifier.DatabaseState.MIGRATED,
                    verifier.verify(connection, master, owner.username()).databaseState());
        }
    }

    @Test
    void refusesUntrackedApplicationObjectsWithoutRemovingThem() throws Exception {
        DatabaseCredentials master = createDatabase("untracked_bootstrap");
        DatabaseCredentials owner = owner(master);
        try (Connection connection = connect(master); Statement statement = connection.createStatement()) {
            DatabaseBootstrap.bootstrap(connection, master.username(), owner);
            statement.execute("CREATE SCHEMA os_data");
            statement.execute("CREATE TABLE public.untracked_address (id integer)");
            statement.execute("CREATE TABLE os_data.flyway_schema_history (id integer)");

            BootstrapVerifier.VerificationException exception = assertThrows(
                    BootstrapVerifier.VerificationException.class,
                    () -> new BootstrapVerifier(null).verify(connection, master, owner.username()));

            assertEquals(BootstrapVerifier.DatabaseState.NOT_EMPTY, exception.report().databaseState());
            assertTrue(exception.report().objects().contains("schema os_data"));
            assertTrue(exception.report().objects().contains("public.untracked_address"));
            try (ResultSet result = statement.executeQuery("SELECT count(*) FROM public.untracked_address")) {
                assertTrue(result.next());
            }
        }
    }

    private static DatabaseCredentials createDatabase(String name) throws Exception {
        try (Connection connection = DriverManager.getConnection(
                POSTGRES.getJdbcUrl(), POSTGRES.getUsername(), POSTGRES.getPassword());
             Statement statement = connection.createStatement()) {
            statement.execute("CREATE DATABASE " + name);
        }
        return new DatabaseCredentials("jdbc:postgresql://" + POSTGRES.getHost() + ":"
                + POSTGRES.getMappedPort(5432) + "/" + name, POSTGRES.getUsername(), POSTGRES.getPassword());
    }

    private static DatabaseCredentials owner(DatabaseCredentials master) {
        return new DatabaseCredentials(master.jdbcUrl(), "address_lookup_owner", "bootstrap-owner-password");
    }

    private static Connection connect(DatabaseCredentials credentials) throws Exception {
        return DriverManager.getConnection(credentials.jdbcUrl(), credentials.username(), credentials.password());
    }

    private static Path migrations() {
        Path root = Path.of("").toAbsolutePath();
        while (root != null && !Files.isDirectory(root.resolve("database/migrations"))) {
            root = root.getParent();
        }
        assertNotNull(root, "Could not locate database/migrations");
        return root.resolve("database/migrations");
    }
}
