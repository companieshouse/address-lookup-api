package uk.gov.companieshouse.addresslookup.dbmigrate;

import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.SQLException;
import java.util.Properties;

import static uk.gov.companieshouse.addresslookup.dbmigrate.MigrationException.Code.DATABASE_UNAVAILABLE;

/**
 * Opens schema-owner connections. Flyway asks for more than one per run (its schema history
 * lock uses a second), so it gets a factory, not a single connection.
 */
@FunctionalInterface
public interface ConnectionFactory {
  Connection open(DatabaseCredentials credentials);

  static ConnectionFactory jdbc() {
    return credentials -> {
      var props = new Properties();
      props.setProperty("user", credentials.username());
      props.setProperty("password", credentials.password());
      props.setProperty("connectTimeout", "10");
      props.setProperty("ApplicationName", "address-lookup-db-migrate");
      try {
        return DriverManager.getConnection(credentials.jdbcUrl(), props);
      } catch (SQLException e) {
        throw new MigrationException(
            DATABASE_UNAVAILABLE,
            "Cannot connect to " + credentials.jdbcUrl() + ": " + e.getMessage(),
            e);
      }
    };
  }
}
