package uk.gov.companieshouse.addresslookup.lambda.upload.runtime;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mockConstruction;

import com.zaxxer.hikari.HikariConfig;
import com.zaxxer.hikari.HikariDataSource;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Properties;
import org.junit.jupiter.api.Test;

class DatabaseConfigurationTest {
  private static final DatabaseProperties DB =
      new DatabaseProperties("proxy.example", 5432, "addresses", "upload_role");

  @Test
  void poolHoldsOneTlsConnectionSignedInAsTheUploadRole() {
    var config = createPool(() -> DatabaseConfiguration.pool(DB, () -> "token"));

    assertEquals(1, config.getMaximumPoolSize());
    assertEquals(0, config.getMinimumIdle());
    var source = assertInstanceOf(IamAuthDataSource.class, config.getDataSource());
    assertEquals("jdbc:postgresql://proxy.example:5432/addresses", source.getUrl());
    assertEquals("upload_role", source.getUsername());
    Properties driver = source.getConnectionProperties();
    assertEquals("require", driver.getProperty("sslmode"));
    assertEquals("address-lookup-upload", driver.getProperty("ApplicationName"));
  }

  @Test
  void dataSourceSignsIamTokensForTheConfiguredEndpointAndRole() {
    var local = new DatabaseProperties("localhost", 1, "addresses", "upload_role");
    // Dummy, non-secret values so the AWS SDK can presign offline.
    Map<String, String> aws =
        Map.of("aws.region", "eu-west-2", "aws.accessKeyId", "test", "aws.secretAccessKey", "test");
    Map<String, String> previous = new HashMap<>();
    aws.forEach((k, v) -> previous.put(k, System.setProperty(k, v)));
    try {
      var config = createPool(() -> new DatabaseConfiguration().dataSource(local));
      var source = (IamAuthDataSource) config.getDataSource();
      var properties = new Properties();

      assertThrows(SQLException.class, () -> source.getConnectionFromDriver(properties));

      String token = properties.getProperty("password");
      assertTrue(token.startsWith("localhost:1/?"), token);
      assertTrue(token.contains("Action=connect"), token);
      assertTrue(token.contains("DBUser=upload_role"), token);
    } finally {
      previous.forEach((k, v) -> {
        if (v == null) System.clearProperty(k);
        else System.setProperty(k, v);
      });
    }
  }

  /** Captures the pool configuration without letting Hikari open a connection. */
  private static HikariConfig createPool(Runnable factory) {
    List<HikariConfig> configs = new ArrayList<>();
    try (var pools =
        mockConstruction(
            HikariDataSource.class,
            (pool, context) -> configs.add((HikariConfig) context.arguments().getFirst()))) {
      factory.run();
    }
    assertEquals(1, configs.size());
    return configs.getFirst();
  }
}
