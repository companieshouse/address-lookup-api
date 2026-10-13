package uk.gov.companieshouse.addresslookup.dbbootstrap;

import org.junit.jupiter.api.Test;

import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class DatabaseCredentialsTest {

    private static final String ROOT_CERT = "/var/task/certs/eu-west-2-bundle.pem";

    @Test
    void readsTheJdbcUrlForm() {
        DatabaseCredentials credentials = DatabaseCredentials.fromJson("""
                {"jdbcUrl":"jdbc:postgresql://cidev-address-rds.cluster-abc.eu-west-2.rds.amazonaws.com:5432/addressdb?sslmode=verify-full&sslrootcert=/var/task/certs/eu-west-2-bundle.pem",
                 "username":"address_lookup_owner","password":"s3cret"}""", ROOT_CERT);

        assertEquals("cidev-address-rds.cluster-abc.eu-west-2.rds.amazonaws.com", credentials.host());
        assertEquals(5432, credentials.port());
        assertEquals("address_lookup_owner", credentials.username());
        assertEquals(Map.of("sslmode", "verify-full", "sslrootcert", ROOT_CERT), credentials.parameters());
    }

    @Test
    void buildsAVerifyFullUrlFromHostPortAndDbname() {
        DatabaseCredentials credentials = DatabaseCredentials.fromJson("""
                {"host":"writer.example","port":6543,"dbname":"addressdb","username":"owner","password":"pw"}""",
                ROOT_CERT);

        assertEquals("jdbc:postgresql://writer.example:6543/addressdb?sslmode=verify-full&sslrootcert=" + ROOT_CERT,
                credentials.jdbcUrl());
        assertEquals(6543, credentials.port());
    }

    @Test
    void defaultsThePort() {
        DatabaseCredentials credentials = new DatabaseCredentials("jdbc:postgresql://writer.example/addressdb", "u", "p");

        assertEquals(5432, credentials.port());
        assertTrue(credentials.parameters().isEmpty());
    }

    @Test
    void neverPrintsThePassword() {
        DatabaseCredentials credentials = new DatabaseCredentials("jdbc:postgresql://h:5432/d", "owner", "s3cret");

        assertFalse(credentials.toString().contains("s3cret"));
        assertTrue(credentials.toString().contains("owner"));
    }

    @Test
    void doesNotEchoInvalidJson() {
        IllegalArgumentException e = assertThrows(IllegalArgumentException.class,
                () -> DatabaseCredentials.fromJson("{\"password\":\"s3cret\"", ROOT_CERT));

        assertFalse(e.getMessage().contains("s3cret"));
        assertEquals(null, e.getCause());
    }

    @Test
    void rejectsIncompleteCredentials() {
        assertThrows(IllegalArgumentException.class,
                () -> DatabaseCredentials.fromJson("{\"username\":\"u\",\"password\":\"p\"}", ROOT_CERT));
        assertThrows(IllegalArgumentException.class, () -> DatabaseCredentials.fromJson("""
                {"jdbcUrl":"jdbc:postgresql://h/d","username":"u","password":""}""", ROOT_CERT));
        assertThrows(IllegalArgumentException.class, () -> DatabaseCredentials.fromJson("""
                {"jdbcUrl":"jdbc:mysql://h/d","username":"u","password":"p"}""", ROOT_CERT));
    }
}
