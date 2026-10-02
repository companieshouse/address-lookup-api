package uk.gov.companieshouse.addresslookup.lambda.schema;

import org.junit.jupiter.api.Test;

import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

class MigratorConfigurationTest {

    private static final Map<String, String> ENV = Map.of(
            "CHANGELOG_BUCKET", "release-bucket",
            "DB_HOST", "cidev-address-rds.cluster-abc.eu-west-2.rds.amazonaws.com",
            "DB_NAME", "addressdb",
            "DB_USERNAME_PARAMETER", "/address-lookup-liquibase-lambda-cidev/db_username",
            "DB_PASSWORD_PARAMETER", "/address-lookup-liquibase-lambda-cidev/db_password");

    @Test
    void alwaysRequiresAnEncryptedConnection() {
        MigratorConfiguration config = MigratorConfiguration.fromEnvironment(ENV);

        assertEquals("jdbc:postgresql://cidev-address-rds.cluster-abc.eu-west-2.rds.amazonaws.com:5432/addressdb?sslmode=require", // trufflehog:ignore
                config.jdbcUrl());
    }

    @Test
    void readsReleasedArtefactsFromTheFixedPrefix() {
        MigratorConfiguration config = MigratorConfiguration.fromEnvironment(ENV);

        assertEquals("address-lookup-api/address-lookup-db-schema-1.0.3.zip", config.changelogKey("1.0.3"));
        assertEquals("aws", config.contexts());
    }

    @Test
    void failsFastOnMissingConfiguration() {
        assertThrows(IllegalArgumentException.class, () -> MigratorConfiguration.fromEnvironment(Map.of()));
    }
}
