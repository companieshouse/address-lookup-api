package uk.gov.companieshouse.addresslookup;

import javax.sql.DataSource;
import liquibase.integration.spring.SpringLiquibase;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.env.Environment;

@Configuration
class LiquibaseConfiguration {

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
