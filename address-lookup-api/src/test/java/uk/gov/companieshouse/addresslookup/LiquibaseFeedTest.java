package uk.gov.companieshouse.addresslookup;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.LinkedHashSet;
import java.util.Properties;
import java.util.Set;
import liquibase.changelog.ChangeLogParameters;
import liquibase.parser.ChangeLogParserFactory;
import liquibase.resource.ClassLoaderResourceAccessor;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.boot.context.config.ConfigDataEnvironmentPostProcessor;
import org.springframework.core.env.MapPropertySource;
import org.springframework.core.env.StandardEnvironment;

/**
 * Verifies the canonical feed contract and real Spring configuration imports
 * without starting an application context or connecting to a database.
 */
class LiquibaseFeedTest {
    private static final String MASTER = "database/changelog-master.xml";

    @Test
    void shouldDiscoverTenOrderedChangelogsWithReviewedIdentitiesAndRollback() throws Exception {
        assertNotNull(getClass().getClassLoader().getResource(MASTER));
        try (var resources = new ClassLoaderResourceAccessor()) {
            var changelog = ChangeLogParserFactory.getInstance().getParser(MASTER, resources)
                    .parse(MASTER, new ChangeLogParameters(), resources);
            Set<String> files = new LinkedHashSet<>();
            Set<String> identities = new LinkedHashSet<>();
            for (var change : changelog.getChangeSets()) {
                files.add(change.getFilePath());
                assertEquals("team-atlas", change.getAuthor());
                assertTrue(change.getId().matches("2026-\\d{3}:\\d{2}"), change.getId());
                assertTrue(identities.add(change.getId()), "Duplicate ID: " + change.getId());
                assertNotNull(change.getComments(), change.getId());
                assertFalse(change.getComments().isBlank(), change.getId());
                assertFalse(change.getRollback().getChanges().isEmpty(), change.getId());
                int group = Integer.parseInt(change.getId().substring(5, 8));
                if (group >= 6) {
                    assertTrue(change.getContextFilter().toString().contains("@local"), change.getId());
                } else {
                    assertTrue(change.getContextFilter().isEmpty(), change.getId());
                }
            }
            assertEquals(10, files.size());
            assertEquals(42, identities.size());
            int group = 1;
            for (String file : files) {
                assertTrue(file.startsWith("database/changelogs/2026-%03d-".formatted(group)), file);
                assertTrue(file.endsWith(group <= 6 ? ".postgres.sql" : ".yaml"), file);
                try (InputStream input = getClass().getClassLoader().getResourceAsStream(file)) {
                    assertNotNull(input, file);
                    String source = new String(input.readAllBytes(), StandardCharsets.UTF_8);
                    String prefix = group <= 6 ? "-- " : "# ";
                    assertTrue(source.contains(prefix + "Jira: ALS-13"), file);
                    assertTrue(source.contains(prefix + "Author: team-atlas"), file);
                    assertTrue(source.matches("(?s).*" + prefix + "Description: [^\\r\\n]+.*"), file);
                    assertTrue(source.matches("(?s).*" + prefix + "Created: \\d{4}-\\d{2}-\\d{2}.*"), file);
                }
                group++;
            }
        }
    }

    @Test
    void shouldIsolateLocalConfigurationAndFixtures() throws Exception {
        for (String environment : new String[]{"production", "staging", "cidev"}) {
            Properties properties = readProperties(environment);
            assertEquals("classpath:" + MASTER, properties.getProperty("spring.liquibase.change-log"));
            assertEquals("!local", properties.getProperty("spring.liquibase.contexts"));
            assertEquals("false", properties.getProperty("spring.liquibase.enabled"));
            assertEquals("none", properties.getProperty("spring.jpa.hibernate.ddl-auto"));
        }
        Properties local = readProperties("local");
        assertEquals("local", local.getProperty("spring.liquibase.contexts"));
        assertEquals("true", local.getProperty("spring.liquibase.enabled"));
        assertTrue(local.getProperty("spring.datasource.url").contains("5433"));
        for (String file : new String[]{"gb_royalmailaddress", "gb_builtaddress",
                "isl_royalmailaddress", "isl_builtaddress"}) {
            assertNotNull(getClass().getClassLoader()
                    .getResource("database/test-data/" + file + ".csv"), file);
        }
        assertNull(getClass().getClassLoader().getResource("db/changelog/db.changelog-master.yaml"));
    }

    @ParameterizedTest
    @ValueSource(strings = {"default", "production", "staging", "cidev", "local"})
    void shouldImportEnvironmentPropertiesThroughSpringConfigData(String profile) {
        var environment = new StandardEnvironment();
        environment.getPropertySources().remove(StandardEnvironment.SYSTEM_ENVIRONMENT_PROPERTY_SOURCE_NAME);
        environment.getPropertySources().addFirst(new MapPropertySource("test",
                java.util.Map.of("spring.config.location", "classpath:application.yaml")));
        if (!profile.equals("default")) {
            environment.setActiveProfiles(profile);
        }
        ConfigDataEnvironmentPostProcessor.applyTo(environment);
        boolean local = profile.equals("local");
        assertEquals("classpath:" + MASTER, environment.getProperty("spring.liquibase.change-log"));
        assertEquals(local ? "local" : "!local", environment.getProperty("spring.liquibase.contexts"));
        assertEquals(local, environment.getProperty("spring.liquibase.enabled", Boolean.class));
        assertEquals(local, environment.getProperty("spring.docker.compose.enabled", Boolean.class));
        assertEquals("none", environment.getProperty("spring.jpa.hibernate.ddl-auto"));
        String properties = "database/" + (profile.equals("default") ? "production" : profile) + ".properties";
        assertTrue(java.util.stream.StreamSupport.stream(
                        environment.getPropertySources().spliterator(), false)
                .anyMatch(source -> source.getName().contains(properties)), properties);
    }

    private Properties readProperties(String environment) throws Exception {
        try (InputStream input = getClass().getClassLoader()
                .getResourceAsStream("database/" + environment + ".properties")) {
            assertNotNull(input, environment);
            Properties properties = new Properties();
            properties.load(input);
            return properties;
        }
    }
}
