package uk.gov.companieshouse.addresslookup.dbmigrate;

import static uk.gov.companieshouse.addresslookup.dbmigrate.MigrationException.Code.DATABASE_UNAVAILABLE;

import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.SQLException;
import java.util.Properties;

/**
 * Opens schema-owner connections. Flyway asks for more than one per run (its schema history
 * lock uses a second), so it gets a factory, not a single connection.
 */
@FunctionalInterface
public interface ConnectionFactory {
  /** Passed to the JDBC driver as a string property. */
  String CONNECT_TIMEOUT_SECONDS = "10";

  String APPLICATION_NAME = "address-lookup-db-migrate";

  Connection open(DatabaseCredentials credentials);

  static ConnectionFactory jdbc() {
    return credentials -> {
      var props = new Properties();
      props.setProperty("user", credentials.username());
      props.setProperty("password", credentials.password());
      props.setProperty("connectTimeout", CONNECT_TIMEOUT_SECONDS);
      props.setProperty("ApplicationName", APPLICATION_NAME);
      try {
        return DriverManager.getConnection(credentials.jdbcUrl(), props);
      } catch (SQLException e) {
        // The driver message and cause can include the full URL, so they are left out.
        throw new MigrationException(
            DATABASE_UNAVAILABLE,
            "Cannot connect to "
                + credentials.sanitisedJdbcUrl()
                + " (SQLState "
                + e.getSQLState()
                + ")");
      }
    };
  }
}
