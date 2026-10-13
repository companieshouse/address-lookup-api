package uk.gov.companieshouse.addresslookup.dbbootstrap;

import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class BootstrapConfigurationTest {

    @Test
    void readsTheEnvironmentWithDefaults() {
        BootstrapConfiguration config = BootstrapConfiguration.fromEnvironment(Map.of(
                "MASTER_CREDENTIALS_PARAMETER", "/address-lookup/cidev/db/master",
                "SCHEMA_OWNER_CREDENTIALS_PARAMETER", "/address-lookup/cidev/db/schema-owner"));

        assertEquals("/address-lookup/cidev/db/master", config.masterParameter());
        assertEquals("/address-lookup/cidev/db/schema-owner", config.schemaOwnerParameter());
        assertEquals("/var/task/certs/eu-west-2-bundle.pem", config.defaultRootCert());
        assertTrue(config.verifyTls());
        assertEquals(Duration.ofSeconds(10), config.timeout());
    }

    @Test
    void canSkipTlsChecks() {
        BootstrapConfiguration config = BootstrapConfiguration.fromEnvironment(Map.of(
                "MASTER_CREDENTIALS_PARAMETER", "m", "SCHEMA_OWNER_CREDENTIALS_PARAMETER", "o",
                "VERIFY_TLS", "false", "CONNECT_TIMEOUT_SECONDS", "3"));

        assertFalse(config.verifyTls());
        assertEquals(Duration.ofSeconds(3), config.timeout());
    }

    @Test
    void requiresBothParameters() {
        assertThrows(IllegalArgumentException.class,
                () -> BootstrapConfiguration.fromEnvironment(Map.of("MASTER_CREDENTIALS_PARAMETER", "m")));
    }
}
