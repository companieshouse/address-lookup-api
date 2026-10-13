package uk.gov.companieshouse.addresslookup.dbbootstrap;

import com.amazonaws.services.lambda.runtime.Context;
import com.amazonaws.services.lambda.runtime.LambdaLogger;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import java.sql.SQLException;
import java.time.Duration;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class HandlerTest {

    private static final BootstrapConfiguration CONFIG = new BootstrapConfiguration(
            "/address-lookup/cidev/db/master", "/address-lookup/cidev/db/schema-owner", "/ca.pem", false,
            Duration.ofSeconds(1));
    private static final DatabaseCredentials MASTER =
            new DatabaseCredentials("jdbc:postgresql://writer:5432/addressdb", "master", "master-secret");
    private static final DatabaseCredentials OWNER =
            new DatabaseCredentials("jdbc:postgresql://writer:5432/addressdb", "address_lookup_owner", "owner-secret");

    @Test
    void bootstrapsAndVerifiesAsTheMaster() throws Exception {
        FakeDatabase db = FakeDatabase.ready();
        Context context = context();

        Map<String, Object> result = handler(db).handleRequest(Map.of("command", "bootstrap"), context);

        assertEquals("bootstrap", result.get("command"));
        assertEquals("EMPTY", result.get("databaseState"));
        assertEquals("address_lookup_owner", result.get("schemaOwner"));
        assertEquals("CREATE EXTENSION IF NOT EXISTS postgis", db.executed.get(0));
        verify(db.connection).close();
        ArgumentCaptor<String> logged = ArgumentCaptor.forClass(String.class);
        verify(context.getLogger()).log(logged.capture());
        assertFalse(logged.getValue().contains("secret"), logged.getValue());
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

    private static Context context() {
        Context context = mock(Context.class);
        when(context.getLogger()).thenReturn(mock(LambdaLogger.class));
        return context;
    }
}
