package uk.gov.companieshouse.addresslookup.dbbootstrap;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.mockito.MockedStatic;

import java.sql.DriverManager;
import java.sql.SQLException;
import java.time.Duration;
import java.util.Map;
import java.util.Properties;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mockStatic;
import static org.mockito.Mockito.verify;

class HandlerTest {

    private static final BootstrapConfiguration CONFIG = new BootstrapConfiguration(
            "/address-lookup/cidev/db/master", "/address-lookup/cidev/db/schema-owner", false,
            Duration.ofSeconds(1));
    private static final DatabaseCredentials MASTER =
            new DatabaseCredentials("jdbc:postgresql://writer:5432/addressdb", "master", "master-secret");
    private static final DatabaseCredentials OWNER =
            new DatabaseCredentials("jdbc:postgresql://writer:5432/addressdb", "address_lookup_owner", "owner-secret");

    @Test
    void bootstrapsAndVerifiesAsTheMaster() throws Exception {
        FakeDatabase db = FakeDatabase.ready();

        Map<String, Object> result = handler(db).handleRequest(Map.of("command", "bootstrap"), null);

        assertEquals("bootstrap", result.get("command"));
        assertEquals("EMPTY", result.get("databaseState"));
        assertEquals("address_lookup_owner", result.get("schemaOwner"));
        assertEquals("CREATE EXTENSION IF NOT EXISTS postgis", db.executed.get(0));
        verify(db.connection).close();
        // The result is also what is logged
        assertFalse(result.toString().contains("secret"), result.toString());
    }

    @Test
    void bootstrapIsTheDefaultCommand() throws Exception {
        FakeDatabase db = FakeDatabase.ready();

        handler(db).handleRequest(Map.of(), null);

        assertFalse(db.executed.isEmpty());
    }

    @Test
    void verifyChangesNothing() throws Exception {
        FakeDatabase db = FakeDatabase.ready();

        Map<String, Object> result = handler(db).handleRequest(Map.of("command", "verify"), null);

        assertEquals("verify", result.get("command"));
        assertTrue(db.executed.isEmpty());
    }

    @Test
    void failsTheInvocationWhenVerificationFails() throws Exception {
        FakeDatabase db = new FakeDatabase();

        assertThrows(BootstrapVerifier.VerificationException.class,
                () -> handler(db).handleRequest(Map.of("command", "verify"), null));
    }

    @Test
    void wrapsDatabaseErrorsWithoutCredentials() {
        Handler handler = new Handler(CONFIG, this::credentials, credentials -> {
            throw new SQLException("Connection refused");
        });

        IllegalStateException e = assertThrows(IllegalStateException.class, () -> handler.handleRequest(Map.of(), null));

        assertTrue(e.getMessage().contains("Connection refused"));
        assertFalse(e.getMessage().contains("secret"));
    }

    @Test
    void rejectsUnknownCommandsBeforeReadingCredentials() {
        Handler handler = new Handler(CONFIG, name -> {
            throw new AssertionError("credentials must not be read");
        });

        assertThrows(IllegalArgumentException.class, () -> handler.handleRequest(Map.of("command", "drop"), null));
    }

    @ParameterizedTest
    @ValueSource(strings = {
            "", "?sslmode=disable", "?sslmode=allow", "?sslmode=prefer", "?sslmode=require", "?sslmode=verify-ca",
            "?sslmode=verify-full&sslmode=require",
            "?sslmode=verify-full&sslfactory=org.postgresql.ssl.NonValidatingFactory",
            "?sslmode=verify-full&sslfactoryarg=custom",
            "?sslmode=verify-full&sslhostnameverifier=example.AcceptAll"
    })
    void rejectsUnsafeMasterUrlsBeforeOpeningEitherConnectionEvenWhenVerificationIsDisabled(String query) {
        DatabaseCredentials master = new DatabaseCredentials(
                "jdbc:postgresql://writer:5432/addressdb" + query, MASTER.username(), MASTER.password());
        Handler handler = new Handler(CONFIG,
                parameter -> parameter.equals(CONFIG.masterParameter()) ? master : OWNER);

        try (MockedStatic<DriverManager> driver = mockStatic(DriverManager.class)) {
            for (String command : new String[] {"bootstrap", "verify"}) {
                IllegalArgumentException error = assertThrows(IllegalArgumentException.class,
                        () -> handler.handleRequest(Map.of("command", command), null));
                assertFalse(error.getMessage().contains(MASTER.password()));
                assertFalse(error.getMessage().contains(master.jdbcUrl()));
            }
            driver.verifyNoInteractions();
        }
    }

    @ParameterizedTest
    @ValueSource(strings = {
            "&sslrootcert=/opt/certs/approved-rds.pem",
            "&sslfactory=org.postgresql.ssl.LibPQFactory&sslrootcert=/opt/certs/approved-rds.pem",
            "&sslfactory=org.postgresql.ssl.DefaultJavaSSLFactory"
    })
    void opensVerifiedMasterConnectionUsingStandardTrustConfiguration(String trustConfiguration) throws Exception {
        FakeDatabase db = FakeDatabase.ready();
        DatabaseCredentials master = new DatabaseCredentials(
                MASTER.jdbcUrl() + "?sslmode=verify-full" + trustConfiguration,
                MASTER.username(), MASTER.password());
        Handler handler = new Handler(CONFIG,
                parameter -> parameter.equals(CONFIG.masterParameter()) ? master : OWNER);

        try (MockedStatic<DriverManager> driver = mockStatic(DriverManager.class)) {
            driver.when(() -> DriverManager.getConnection(eq(master.jdbcUrl()), any(Properties.class)))
                    .thenReturn(db.connection);

            assertEquals("verify", handler.handleRequest(Map.of("command", "verify"), null).get("command"));

            driver.verify(() -> DriverManager.getConnection(eq(master.jdbcUrl()), any(Properties.class)));
            verify(db.connection).close();
        }
    }

    private Handler handler(FakeDatabase db) {
        return new Handler(CONFIG, this::credentials, credentials -> {
            assertEquals(MASTER, credentials, "connects as the master");
            return db.connection;
        });
    }

    private DatabaseCredentials credentials(String parameter) {
        return switch (parameter) {
            case "/address-lookup/cidev/db/master" -> MASTER;
            case "/address-lookup/cidev/db/schema-owner" -> OWNER;
            default -> throw new AssertionError(parameter);
        };
    }
}
