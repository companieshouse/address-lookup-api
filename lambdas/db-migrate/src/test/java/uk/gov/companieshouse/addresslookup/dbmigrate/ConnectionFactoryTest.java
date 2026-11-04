package uk.gov.companieshouse.addresslookup.dbmigrate;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ConnectionFactoryTest {

  private final ConnectionFactory connectionFactory = ConnectionFactory.jdbc();

  @Test
  void unreachableDatabaseFailsAsDatabaseUnavailable() {
    // Port 1 is never open, so the connection is refused straight away.
    var credentials = new DatabaseCredentials("jdbc:postgresql://127.0.0.1:1/address_lookup", "owner", "s3cret");

    var e = assertThrows(MigrationException.class, () -> connectionFactory.open(credentials));

    assertEquals(MigrationException.Code.DATABASE_UNAVAILABLE, e.code());
    assertTrue(e.getMessage().startsWith("DATABASE_UNAVAILABLE: Cannot connect to jdbc:postgresql://127.0.0.1:1/address_lookup"));
    assertFalse(e.getMessage().contains("s3cret"));
  }

  @Test
  void passwordEmbeddedInTheJdbcUrlIsNotRevealed() {
    var credentials =
        new DatabaseCredentials("jdbc:postgresql://owner:urlsecret@127.0.0.1:1/address_lookup?password=urlsecret", "owner", "p");

    var e = assertThrows(MigrationException.class, () -> connectionFactory.open(credentials));

    assertFalse(e.getMessage().contains("urlsecret"));
  }

  @Test
  void unsupportedJdbcUrlFailsAsDatabaseUnavailable() {
    var credentials = new DatabaseCredentials("jdbc:unknown://h/d", "u", "p");

    var e = assertThrows(MigrationException.class, () -> connectionFactory.open(credentials));

    assertEquals(MigrationException.Code.DATABASE_UNAVAILABLE, e.code());
  }
}

