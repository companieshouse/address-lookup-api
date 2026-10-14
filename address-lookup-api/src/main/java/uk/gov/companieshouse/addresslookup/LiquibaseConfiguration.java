package uk.gov.companieshouse.addresslookup;

import javax.sql.DataSource;
import liquibase.integration.spring.SpringLiquibase;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.env.Environment;

/**
 * Configures Liquibase for the API as an explicit opt-in.
 *
 * <p>Liquibase runs at startup only when {@code spring.liquibase.enabled=true}. That is
 * set by the {@code local} profile and by the integration tests, and defaults to
 * {@code false} so production schema changes stay with the dedicated migrator.
 */
@Configuration
class LiquibaseConfiguration {

    /**
     * Creates the Liquibase runner.
     *
     * <p>The change log comes from {@code spring.liquibase.change-log}, falling back to the
     * master changelog. Contexts come from {@code spring.liquibase.contexts}, defaulting to none.
     *
     * @param dataSource  the database to migrate
     * @param environment source of the {@code spring.liquibase.*} properties
     * @return a Liquibase runner that only executes when enabled
     */
    @Bean
    SpringLiquibase liquibase(DataSource dataSource, Environment environment) {
        SpringLiquibase liquibase = new SpringLiquibase();
        liquibase.setDataSource(dataSource);
        liquibase.setChangeLog(environment.getProperty(
                "spring.liquibase.change-log",
                "classpath:db/changelog/db.changelog-master.yaml"));
        liquibase.setContexts(environment.getProperty("spring.liquibase.contexts", ""));

        // Keep migrations opt-in: production schema changes use the dedicated
        // migrator, not API startup. Local development enables Liquibase explicitly.
        liquibase.setShouldRun(environment.getProperty(
                "spring.liquibase.enabled", Boolean.class, false));

        return liquibase;
    }
}
