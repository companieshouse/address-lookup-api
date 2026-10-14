package uk.gov.companieshouse.addresslookup.dbbootstrap;

import java.time.Duration;
import java.util.Map;

/**
 * Environment variables set by terraform/groups/aurora.
 *
 * @param masterParameter      Parameter Store name of the master credentials (MASTER_CREDENTIALS_PARAMETER)
 * @param schemaOwnerParameter Parameter Store name of the schema-owner credentials (SCHEMA_OWNER_CREDENTIALS_PARAMETER)
 * @param verifyTls            whether verify checks rds.force_ssl, ssl_min_protocol_version and probes TLS 1.2/1.3 (VERIFY_TLS)
 * @param timeout              connect and TLS probe timeout (CONNECT_TIMEOUT_SECONDS)
 */
public record BootstrapConfiguration(String masterParameter, String schemaOwnerParameter, boolean verifyTls,
                                     Duration timeout) {

    public static BootstrapConfiguration fromEnvironment(Map<String, String> env) {
        return new BootstrapConfiguration(
                required(env, "MASTER_CREDENTIALS_PARAMETER"),
                required(env, "SCHEMA_OWNER_CREDENTIALS_PARAMETER"),
                !"false".equalsIgnoreCase(env.getOrDefault("VERIFY_TLS", "true")),
                Duration.ofSeconds(Long.parseLong(env.getOrDefault("CONNECT_TIMEOUT_SECONDS", "10"))));
    }

    private static String required(Map<String, String> env, String name) {
        String value = env.get(name);
        DatabaseCredentials.require(value != null && !value.isBlank(), name + " is not set");
        return value;
    }
}
