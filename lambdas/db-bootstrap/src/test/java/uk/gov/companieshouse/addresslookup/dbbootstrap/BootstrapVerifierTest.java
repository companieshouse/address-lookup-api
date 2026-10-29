package uk.gov.companieshouse.addresslookup.dbbootstrap;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;
import static uk.gov.companieshouse.addresslookup.dbbootstrap.FakeDatabase.row;
import static uk.gov.companieshouse.addresslookup.dbbootstrap.TlsProtocolProbe.Result.ACCEPTED;
import static uk.gov.companieshouse.addresslookup.dbbootstrap.TlsProtocolProbe.Result.REFUSED;

class BootstrapVerifierTest {

    private static final DatabaseCredentials MASTER = new DatabaseCredentials(
            "jdbc:postgresql://writer.example:5432/addressdb?sslmode=require", "master", "pw");
    private static final String OWNER = "address_lookup_owner";

    private final TlsProtocolProbe probe = mock(TlsProtocolProbe.class);

    @BeforeEach
    void tls13Only() throws Exception {
        when(probe.probe("writer.example", 5432, "TLSv1.2")).thenReturn(new TlsProtocolProbe.Outcome(REFUSED, "protocol_version"));
        when(probe.probe("writer.example", 5432, "TLSv1.3")).thenReturn(new TlsProtocolProbe.Outcome(ACCEPTED, null));
    }

    @Test
    void passesAnEmptyPreparedDatabase() throws Exception {
        BootstrapVerifier.Report report = new BootstrapVerifier(probe).verify(FakeDatabase.ready().connection, MASTER, OWNER);

        assertEquals("3.6.0", report.postgisVersion());
        assertEquals(BootstrapVerifier.DatabaseState.EMPTY, report.databaseState());
        assertEquals("1", report.forceSsl());
        assertEquals("TLSv1.3", report.sslMinProtocolVersion());
        assertEquals("REFUSED", report.tls12());
        assertEquals("ACCEPTED", report.tls13());
        assertTrue(report.failures().isEmpty());
    }

    @Test
    void acceptsADatabaseFlywayHasMigrated() throws Exception {
        FakeDatabase db = FakeDatabase.ready()
                .returns("relname = 'flyway_schema_history'", row("public"))
                .returns("UNION ALL", row("os_data.address"));

        BootstrapVerifier.Report report = new BootstrapVerifier(probe).verify(db.connection, MASTER, OWNER);

        assertEquals(BootstrapVerifier.DatabaseState.MIGRATED, report.databaseState());
    }

    @Test
    void acceptsTheBooleanRepresentationOfForceSsl() throws Exception {
        FakeDatabase db = FakeDatabase.ready().returns("rds.force_ssl", row("on"));

        BootstrapVerifier.Report report = new BootstrapVerifier(probe).verify(db.connection, MASTER, OWNER);

        assertEquals("on", report.forceSsl());
        assertTrue(report.failures().isEmpty());
    }

    @Test
    void failsWhenApplicationObjectsExistWithoutFlywayHistory() throws Exception {
        FakeDatabase db = FakeDatabase.ready().returns("UNION ALL", row("public.address"), row("schema os_data"));

        BootstrapVerifier.VerificationException e = assertThrows(BootstrapVerifier.VerificationException.class,
                () -> new BootstrapVerifier(probe).verify(db.connection, MASTER, OWNER));

        assertEquals(BootstrapVerifier.DatabaseState.NOT_EMPTY, e.report().databaseState());
        assertEquals(List.of("public.address", "schema os_data"), e.report().objects());
        assertTrue(e.getMessage().contains("no flyway_schema_history"), e.getMessage());
    }

    @Test
    void failsWithoutPostgisOrTheSchemaOwner() throws Exception {
        FakeDatabase db = new FakeDatabase();

        BootstrapVerifier.VerificationException e = assertThrows(BootstrapVerifier.VerificationException.class,
                () -> new BootstrapVerifier(null).verify(db.connection, MASTER, OWNER));

        assertTrue(e.report().failures().contains("postgis is not in pg_extension"));
        assertTrue(e.report().failures().contains("role address_lookup_owner does not exist"));
    }

    @Test
    void failsWhenTheSchemaOwnerIsPrivileged() throws Exception {
        FakeDatabase db = FakeDatabase.ready().returns("r.rolsuper", row(true, true, true, false, false, false));

        BootstrapVerifier.VerificationException e = assertThrows(BootstrapVerifier.VerificationException.class,
                () -> new BootstrapVerifier(probe).verify(db.connection, MASTER, OWNER));

        assertEquals(List.of(
                OWNER + " is a superuser",
                OWNER + " is a member of rds_superuser",
                OWNER + " lacks CONNECT or CREATE on the database",
                OWNER + " lacks USAGE or CREATE on schema public"), e.report().failures());
    }

    @Test
    void failsWhenTheSchemaOwnerIsTheMaster() {
        BootstrapVerifier.VerificationException e = assertThrows(BootstrapVerifier.VerificationException.class,
                () -> new BootstrapVerifier(probe).verify(FakeDatabase.ready().connection, MASTER, "master"));

        assertTrue(e.report().failures().contains("the schema owner is the master user"));
    }

    @Test
    void failsWhenTls12IsAcceptedOrTlsIsNotForced() throws Exception {
        FakeDatabase db = FakeDatabase.ready()
                .returns("rds.force_ssl", row("0"))
                .returns("ssl_min_protocol_version", row("TLSv1.2"))
                .returns("pg_stat_ssl", row("TLSv1.2"));
        when(probe.probe("writer.example", 5432, "TLSv1.2")).thenReturn(new TlsProtocolProbe.Outcome(ACCEPTED, null));

        BootstrapVerifier.VerificationException e = assertThrows(BootstrapVerifier.VerificationException.class,
                () -> new BootstrapVerifier(probe).verify(db.connection, MASTER, OWNER));

        assertEquals(4, e.report().failures().size(), e.report().failures().toString());
        assertTrue(e.getMessage().contains("rds.force_ssl is 0"));
        assertTrue(e.getMessage().contains("ssl_min_protocol_version is TLSv1.2"));
        assertTrue(e.getMessage().contains("this connection uses TLSv1.2"));
        assertTrue(e.getMessage().contains("TLS 1.2 client was not refused"));
    }

    @Test
    void failsWhenTls13IsRefused() throws Exception {
        when(probe.probe("writer.example", 5432, "TLSv1.3")).thenReturn(new TlsProtocolProbe.Outcome(REFUSED, "PKIX path"));

        BootstrapVerifier.VerificationException e = assertThrows(BootstrapVerifier.VerificationException.class,
                () -> new BootstrapVerifier(probe).verify(FakeDatabase.ready().connection, MASTER, OWNER));

        assertTrue(e.getMessage().contains("TLS 1.3 client was not accepted"), e.getMessage());
    }

    @Test
    void skipsTlsChecksWithoutAProbe() throws Exception {
        FakeDatabase db = FakeDatabase.ready().returns("rds.force_ssl", row("0"));

        BootstrapVerifier.Report report = new BootstrapVerifier(null).verify(db.connection, MASTER, OWNER);

        assertNull(report.forceSsl());
        assertNull(report.tls12());
    }
}
