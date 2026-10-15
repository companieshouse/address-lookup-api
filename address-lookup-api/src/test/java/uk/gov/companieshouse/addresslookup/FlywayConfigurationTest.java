package uk.gov.companieshouse.addresslookup;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.io.InputStream;
import java.util.Arrays;
import java.util.List;
import java.util.Properties;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.core.io.ClassPathResource;
import org.springframework.core.io.support.PathMatchingResourcePatternResolver;
import org.springframework.mock.env.MockEnvironment;
import org.springframework.util.ClassUtils;

class FlywayConfigurationTest {

    private static final String MIGRATION_LOCATIONS = "classpath:db/migrations";
    private static final String LOCAL_LOCATIONS = "classpath:db/migrations,classpath:db/local";

    @Test
    void enablesFlywayByDefaultAndUsesProductionMigrationsOnly() throws IOException {
        Properties properties = loadProperties("application.properties");

        assertEquals("true", properties.getProperty("spring.flyway.enabled"));
        assertEquals(MIGRATION_LOCATIONS, properties.getProperty("spring.flyway.locations"));
    }

    @Test
    void keepsFlywayEnabledWhenLegacyFlagIsFalse() throws IOException {
        Properties properties = loadProperties("application.properties");
        MockEnvironment environment = new MockEnvironment()
                .withProperty("FLYWAY_ENABLED", "false");

        assertEquals(
                "true",
                environment.resolveRequiredPlaceholders(
                        properties.getProperty("spring.flyway.enabled")));
    }

    @Test
    void enablesFlywayForLocalProfileAndIncludesLocalSeeds() throws IOException {
        Properties properties = loadProperties("application-local.properties");

        assertEquals("true", properties.getProperty("spring.flyway.enabled"));
        assertEquals(LOCAL_LOCATIONS, properties.getProperty("spring.flyway.locations"));
    }

    @Test
    void enablesFlywayWithBothLocationsInOwnerConfirmedIntegrationTests() {
        assertFlywayTestProperties(AddressTestBaseIT.class);
        assertFlywayTestProperties(AddressLookupApplicationIT.class);
    }

    @Test
    void packagesFlywayAndPostgresqlSupportWithoutLiquibase() throws IOException {
        Properties flywayProperties = loadProperties(
                "/META-INF/maven/org.flywaydb/flyway-core/pom.properties");

        assertEquals("13.10.0", flywayProperties.getProperty("version"));
        assertTrue(ClassUtils.isPresent(
                "org.flywaydb.database.postgresql.PostgreSQLDatabaseType",
                getClass().getClassLoader()));
        assertFalse(ClassUtils.isPresent(
                "liquibase.integration.spring.SpringLiquibase",
                getClass().getClassLoader()));
    }

    @Test
    void packagesProductionMigrationsAndLocalSeedResourcesSeparately() throws IOException {
        PathMatchingResourcePatternResolver resolver = new PathMatchingResourcePatternResolver();

        assertTrue(resolver.getResources("classpath*:db/migrations/*.sql").length > 0);
        assertTrue(resolver.getResources("classpath*:db/local/*.sql").length > 0);
    }

    private static Properties loadProperties(String classpathResource) throws IOException {
        Properties properties = new Properties();
        try (InputStream input = new ClassPathResource(classpathResource).getInputStream()) {
            properties.load(input);
        }
        return properties;
    }

    private static void assertFlywayTestProperties(Class<?> testClass) {
        SpringBootTest springBootTest = testClass.getDeclaredAnnotation(SpringBootTest.class);
        assertTrue(springBootTest != null, testClass.getSimpleName() + " needs @SpringBootTest");

        List<String> properties = Arrays.asList(springBootTest.properties());
        assertTrue(properties.contains("FLYWAY_ENABLED=false"), properties.toString());
        assertFalse(
                properties.stream().anyMatch(property -> property.startsWith("spring.flyway.enabled=")),
                properties.toString());
        assertTrue(properties.contains("spring.flyway.locations=" + LOCAL_LOCATIONS), properties.toString());
    }
}
