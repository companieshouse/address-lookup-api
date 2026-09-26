package uk.gov.companieshouse.addresslookup.lamda.schema;

import org.junit.jupiter.api.Test;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class MigratorConfigurationTest {

    private static final Map<String, String> ENV = Map.of(
            "CHANGELOG_BUCKET", "release-bucket",
            "DB_HOST", "cidev-address-rds.cluster-abc.eu-west-2.rds.amazonaws.com",
            "DB_NAME", "addressdb",
            "DB_USERNAME_PARAMETER", "/address-lookup-schema-migrator-cidev/db_username",
            "DB_PASSWORD_PARAMETER", "/address-lookup-schema-migrator-cidev/db_password");

    @Test
    void alwaysVerifiesTheServerCertificate() throws Exception {
        MigratorConfiguration config = MigratorConfiguration.fromEnvironment(ENV);

        assertTrue(config.jdbcUrl().startsWith(
                "jdbc:postgresql://cidev-address-rds.cluster-abc.eu-west-2.rds.amazonaws.com:5432/addressdb?sslmode=verify-full&sslrootcert=")); // trufflehog:ignore
        Path bundle = Path.of(config.jdbcUrl().substring(config.jdbcUrl().indexOf("sslrootcert=") + 12));
        assertTrue(Files.readString(bundle).contains("BEGIN CERTIFICATE"));
    }

    @Test
    void readsReleasedArtefactsFromTheFixedPrefix() throws Exception {
        MigratorConfiguration config = MigratorConfiguration.fromEnvironment(ENV);

        assertEquals("address-lookup-api/address-lookup-db-schema-1.0.3.zip", config.changelogKey("1.0.3"));
        assertEquals("aws", config.contexts());
    }

    @Test
    void failsFastOnMissingConfiguration() {
        assertThrows(IllegalArgumentException.class, () -> MigratorConfiguration.fromEnvironment(Map.of()));
    }
}
