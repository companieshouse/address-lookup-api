package uk.gov.companieshouse.addresslookup.dbbootstrap;

import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.mockito.InOrder;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static uk.gov.companieshouse.addresslookup.dbbootstrap.FakeDatabase.row;

class DatabaseBootstrapTest {

    private static final DatabaseCredentials OWNER =
            new DatabaseCredentials("jdbc:postgresql://writer:5432/addressdb", "address_lookup_owner", "s3cret");

    @Test
    void installsPostgisAndCreatesTheSchemaOwner() throws Exception {
        FakeDatabase db = new FakeDatabase();

        DatabaseBootstrap.bootstrap(db.connection, "master", OWNER);

        assertEquals(List.of(
                "CREATE EXTENSION IF NOT EXISTS postgis",
                "CREATE ROLE \"address_lookup_owner\" LOGIN",
                "GRANT CONNECT, CREATE ON DATABASE \"addressdb\" TO \"address_lookup_owner\"",
                "GRANT USAGE, CREATE ON SCHEMA public TO \"address_lookup_owner\""), db.executed);
        verify(db.pgConnection).alterUserPassword(eq("address_lookup_owner"), org.mockito.ArgumentMatchers.any(char[].class),
                eq("scram-sha-256"));
    }

    @Test
    void reRunningUpdatesTheExistingRole() throws Exception {
        FakeDatabase db = new FakeDatabase().returns("SELECT 1 FROM pg_roles", row(1));

        DatabaseBootstrap.bootstrap(db.connection, "master", OWNER);

        assertEquals("ALTER ROLE \"address_lookup_owner\" LOGIN", db.executed.get(1));
        assertTrue(db.executed.stream().noneMatch(sql -> sql.startsWith("CREATE ROLE")));
    }

    @Test
    void setsTheHashedPasswordWithoutPuttingItInSql() throws Exception {
        FakeDatabase db = new FakeDatabase();
        ArgumentCaptor<char[]> password = ArgumentCaptor.forClass(char[].class);

        DatabaseBootstrap.bootstrap(db.connection, "master", OWNER);

        verify(db.pgConnection).alterUserPassword(eq("address_lookup_owner"), password.capture(), eq("scram-sha-256"));
        assertTrue(db.executed.stream().noneMatch(sql -> sql.contains("s3cret")));
        assertArrayEquals(new char[6], password.getValue(), "the password copy is wiped after use");
    }

    @Test
    void neverUsesTheMasterAsTheSchemaOwner() throws Exception {
        FakeDatabase db = new FakeDatabase();

        assertThrows(IllegalArgumentException.class,
                () -> DatabaseBootstrap.bootstrap(db.connection, "address_lookup_owner", OWNER));
        verify(db.connection, never()).createStatement();
    }

    @Test
    void restoresAutoCommit() throws Exception {
        FakeDatabase db = new FakeDatabase();
        when(db.connection.getAutoCommit()).thenReturn(false);

        DatabaseBootstrap.bootstrap(db.connection, "master", OWNER);

        InOrder order = inOrder(db.connection);
        order.verify(db.connection).setAutoCommit(true);
        order.verify(db.connection).setAutoCommit(false);
    }

    @Test
    void passesTheRoleNameAsABindParameterOnly() throws Exception {
        FakeDatabase db = new FakeDatabase();
        String hostile = "owner\"; DROP DATABASE addressdb; --";

        DatabaseBootstrap.bootstrap(db.connection, "master", new DatabaseCredentials(OWNER.jdbcUrl(), hostile, "pw"));

        assertTrue(db.prepared.stream().noneMatch(sql -> sql.contains(hostile)), db.prepared.toString());
        assertTrue(db.prepared.stream().anyMatch(sql -> sql.startsWith("SELECT format(")));
    }
}
