package uk.gov.companieshouse.addresslookup.dbmigrate;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;

class DatabaseCredentialsTest {

  @Test
  void parsesHostPortShape() {
    var credentials =
        DatabaseCredentials.fromJson(
            """
            {"host":"db.cluster.local","port":5432,"dbname":"address_lookup",
             "username":"schema_owner","password":"s3cret"}""");
    assertEquals("jdbc:postgresql://db.cluster.local:5432/address_lookup?sslmode=require", credentials.jdbcUrl());
    assertEquals("schema_owner", credentials.username());
    assertEquals("s3cret", credentials.password());
  }

  @Test
  void prefersExplicitJdbcUrl() {
    var credentials =
        DatabaseCredentials.fromJson(
            """
            {"jdbcUrl":"jdbc:postgresql://h:6543/db?sslmode=verify-full","username":"u","password":"p"}""");
    assertEquals("jdbc:postgresql://h:6543/db?sslmode=verify-full", credentials.jdbcUrl());
  }

  @ParameterizedTest
  @ValueSource(
      strings = {
        "not json",
        "[]",
        "{\"host\":\"h\",\"dbname\":\"d\",\"username\":\"u\"}",
        "{\"jdbcUrl\":\"jdbc:mysql://h/d\",\"username\":\"u\",\"password\":\"p\"}"
      })
  void rejectsIncompleteOrForeignCredentials(String dbCredentialsJson) {
    var migrationException = assertThrows(MigrationException.class, () -> DatabaseCredentials.fromJson(dbCredentialsJson));
    assertEquals(MigrationException.Code.CREDENTIALS_INVALID, migrationException.code());
  }

  @Test
  void sanitisesUserInfoAndPropertiesFromTheJdbcUrl() {
    var credentials =
        new DatabaseCredentials(
            "jdbc:postgresql://owner:s3cret@db.local:5432/address_lookup?user=owner&password=s3cret", "u", "p");

    assertEquals("jdbc:postgresql://db.local:5432/address_lookup", credentials.sanitisedJdbcUrl());
    assertFalse(credentials.toString().contains("s3cret"));
  }

  @Test
  void neverRendersThePassword() {
    var credentials = new DatabaseCredentials("jdbc:postgresql://h/d", "u", "s3cret");
    assertFalse(credentials.toString().contains("s3cret"));
    var migrationException =
        assertThrows(
            MigrationException.class, () -> DatabaseCredentials.fromJson("{\"password\":\"s3cret\" broken"));
    assertFalse(migrationException.getMessage().contains("s3cret"));
  }
}
