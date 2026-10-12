package uk.gov.companieshouse.addresslookup;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.flywaydb.core.Flyway;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Properties;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.boot.context.config.ConfigDataEnvironmentPostProcessor;
import org.springframework.core.env.MapPropertySource;
import org.springframework.core.env.StandardEnvironment;

/**
 * Verifies Flyway discovery, environment isolation and Spring profile wiring
 * without connecting to a database.
 */
class FlywayFeedTest {
    private static final Pattern MIGRATION_NAME =
            Pattern.compile("V(\\d+(?:\\.\\d+)*)__([a-z0-9_]+)\\.sql");

    @Test
    void shouldResolveThePinnedFlywayCommunityVersion() {
        String flywayJar = Flyway.class.getProtectionDomain().getCodeSource().getLocation().toString();
        assertTrue(flywayJar.contains("flyway-core-13.10.0.jar"), flywayJar);
    }

    @Test
    void shouldDiscoverOrderedSchemaAndLocalMigrationsWithUniqueVersions() throws Exception {
        List<String> schema = migrationNames("db/migration");
        List<String> local = migrationNames("db/local-migration");
        assertEquals(List.of(
                "V2026.001__create_address_tables.sql",
                "V2026.002__create_os_control_schema.sql",
                "V2026.003__create_os_data_schema_and_tables.sql",
                "V2026.004__create_os_stage_schema_and_tables.sql",
                "V2026.005__create_address_lookup_view.sql"), schema);
        assertEquals(List.of(
                "V2026.006__create_local_seed_geometry_trigger.sql",
                "V2026.007__seed_gb_royalmailaddress.sql",
                "V2026.008__seed_gb_builtaddress.sql",
                "V2026.009__seed_isl_royalmailaddress.sql",
                "V2026.010__seed_isl_builtaddress.sql"), local);

        var versions = new java.util.HashSet<String>();
        for (String folder : List.of("db/migration", "db/local-migration")) {
            for (String name : migrationNames(folder)) {
                Matcher match = MIGRATION_NAME.matcher(name);
                assertTrue(match.matches(), name);
                assertTrue(versions.add(match.group(1)), "Duplicate version " + match.group(1));
                String sql = readResource(folder + "/" + name);
                assertTrue(sql.contains("-- Jira: ALS-13"), name);
                assertTrue(sql.contains("-- Author: team-atlas"), name);
                assertTrue(sql.contains("-- Created: 2026-10-12"), name);
                assertTrue(sql.contains("-- Source mapping: "), name);
                assertFalse(sql.contains("--changeset "), name);
                assertFalse(sql.contains("--rollback"), name);
                assertFalse(sql.contains("--liquibase formatted sql"), name);
            }
        }
        assertEquals(10, versions.size());
        assertNull(getClass().getClassLoader().getResource("database/test-data/gb_royalmailaddress.csv"));
    }

    @Test
    void shouldKeepEnvironmentCliLocationsAndSafetySettingsIsolated() throws Exception {
        Properties common = readProperties("database/config/flyway/common.conf");
        assertEquals("filesystem:database/migrations/schema", common.getProperty("flyway.locations"));
        assertEquals("public", common.getProperty("flyway.defaultSchema"));
        assertEquals("public", common.getProperty("flyway.schemas"));
        assertEquals("true", common.getProperty("flyway.validateMigrationNaming"));
        assertEquals("true", common.getProperty("flyway.validateOnMigrate"));
        assertEquals("false", common.getProperty("flyway.baselineOnMigrate"));
        assertEquals("true", common.getProperty("flyway.cleanDisabled"));
        assertEquals("false", common.getProperty("flyway.outOfOrder"));

        Properties local = readProperties("database/config/flyway/local.conf");
        assertEquals("filesystem:database/migrations/schema,filesystem:database/migrations/local",
                local.getProperty("flyway.locations"));
        for (String environment : List.of("production", "staging", "cidev")) {
            Properties properties = readProperties("database/config/flyway/" + environment + ".conf");
            assertEquals("filesystem:database/migrations/schema",
                    properties.getProperty("flyway.locations"), environment);
            assertFalse(properties.getProperty("flyway.locations").contains("/local"), environment);
        }
    }

    @ParameterizedTest
    @ValueSource(strings = {"default", "production", "staging", "cidev", "local"})
    void shouldImportOnlyTheExplicitLocalSpringFlywayConfiguration(String profile) {
        var environment = new StandardEnvironment();
        environment.getPropertySources().remove(StandardEnvironment.SYSTEM_ENVIRONMENT_PROPERTY_SOURCE_NAME);
        environment.getPropertySources().addFirst(new MapPropertySource("test",
                java.util.Map.of("spring.config.location", "classpath:application.yaml")));
        if (!profile.equals("default")) {
            environment.setActiveProfiles(profile);
        }
        ConfigDataEnvironmentPostProcessor.applyTo(environment);

        boolean local = profile.equals("local");
        assertEquals(local, environment.getProperty("spring.flyway.enabled", Boolean.class));
        assertEquals("false", environment.getProperty("spring.flyway.baseline-on-migrate"));
        assertEquals("true", environment.getProperty("spring.flyway.clean-disabled"));
        assertEquals("true", environment.getProperty("spring.flyway.validate-on-migrate"));
        assertEquals("true", environment.getProperty("spring.flyway.validate-migration-naming"));
        assertEquals("public", environment.getProperty("spring.flyway.default-schema"));
        assertEquals("none", environment.getProperty("spring.jpa.hibernate.ddl-auto"));
        assertEquals(local, environment.getProperty("spring.docker.compose.enabled", Boolean.class));
        if (local) {
            assertEquals("classpath:db/migration,classpath:db/local-migration",
                    environment.getProperty("spring.flyway.locations"));
            assertTrue(environment.getProperty("spring.datasource.url").contains("5433"));
            assertNotNull(environment.getProperty("spring.datasource.username"));
        } else {
            assertNull(environment.getProperty("spring.flyway.locations"));
        }
    }

    private List<String> migrationNames(String resourceDirectory) throws Exception {
        var url = getClass().getClassLoader().getResource(resourceDirectory);
        assertNotNull(url, resourceDirectory);
        try (var files = Files.list(Path.of(url.toURI()))) {
            return files.map(path -> path.getFileName().toString()).sorted().toList();
        }
    }

    private String readResource(String resource) throws Exception {
        try (InputStream input = getClass().getClassLoader().getResourceAsStream(resource)) {
            assertNotNull(input, resource);
            return new String(input.readAllBytes(), java.nio.charset.StandardCharsets.UTF_8);
        }
    }

    private Properties readProperties(String resource) throws Exception {
        try (InputStream input = getClass().getClassLoader().getResourceAsStream(resource)) {
            assertNotNull(input, resource);
            Properties properties = new Properties();
            properties.load(input);
            return properties;
        }
    }
}
