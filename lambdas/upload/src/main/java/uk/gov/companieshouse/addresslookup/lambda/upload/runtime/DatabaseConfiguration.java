package uk.gov.companieshouse.addresslookup.lambda.upload.runtime;

import com.zaxxer.hikari.HikariConfig;
import com.zaxxer.hikari.HikariDataSource;
import java.util.Properties;
import java.util.function.Supplier;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import software.amazon.awssdk.auth.credentials.DefaultCredentialsProvider;
import software.amazon.awssdk.regions.providers.DefaultAwsRegionProviderChain;
import software.amazon.awssdk.services.rds.RdsUtilities;

/** Connects to RDS Proxy as the upload role using IAM authentication over TLS. */
@Configuration(proxyBeanMethods = false)
@EnableConfigurationProperties(DatabaseProperties.class)
public class DatabaseConfiguration {
  @Bean(destroyMethod = "close")
  HikariDataSource dataSource(DatabaseProperties db) {
    return pool(db, iamTokens(db));
  }

  static HikariDataSource pool(DatabaseProperties db, Supplier<String> token) {
    var driver = new Properties();
    driver.setProperty("sslmode", "require");
    driver.setProperty("connectTimeout", "10");
    driver.setProperty("socketTimeout", "840");
    // Both avoid SET statements at connection start-up, which would pin RDS Proxy sessions.
    driver.setProperty("assumeMinServerVersion", "9.0");
    driver.setProperty("ApplicationName", "address-lookup-upload");
    String url = "jdbc:postgresql://" + db.host() + ":" + db.port() + "/" + db.name();
    var config = new HikariConfig();
    config.setDataSource(new IamAuthDataSource(url, db.user(), driver, token));
    config.setMaximumPoolSize(1);
    config.setMinimumIdle(0);
    return new HikariDataSource(config);
  }

  private static Supplier<String> iamTokens(DatabaseProperties db) {
    var utilities =
        RdsUtilities.builder()
            .region(DefaultAwsRegionProviderChain.builder().build().getRegion())
            .credentialsProvider(DefaultCredentialsProvider.builder().build())
            .build();
    return () ->
        utilities.generateAuthenticationToken(
            r -> r.hostname(db.host()).port(db.port()).username(db.user()));
  }
}
