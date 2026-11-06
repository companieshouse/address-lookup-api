package uk.gov.companieshouse.addresslookup.lambda.upload.runtime;

import java.sql.Connection;
import java.sql.SQLException;
import java.util.Properties;
import java.util.function.Supplier;
import org.springframework.jdbc.datasource.DriverManagerDataSource;

/** Signs in with a fresh short-lived IAM token for every new physical connection. */
final class IamAuthDataSource extends DriverManagerDataSource {
  private final transient Supplier<String> token;

  IamAuthDataSource(String url, String user, Properties connectionProperties, Supplier<String> token) {
    super(url, user, null);
    setConnectionProperties(connectionProperties);
    this.token = token;
  }

  @Override
  protected Connection getConnectionFromDriver(Properties properties) throws SQLException {
    properties.setProperty("password", token.get());
    return super.getConnectionFromDriver(properties);
  }
}
