package uk.gov.companieshouse.addresslookup.dbmigrate;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;

class DatabaseCredentialsTest {

  @Test
  void parsesHostPortShape() {
    var credentials =
        DatabaseCredentials.parse(
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
        DatabaseCredentials.parse(
            """
            {"jdbcUrl":"jdbc:postgresql://h:6543/db?sslmode=verify-full","username":"u","password":"p"}""");
    assertEquals("jdbc:postgresql://h:6543/db?sslmode=verify-full", credentials.jdbcUrl());
  }

  @Test
  void rejectsIncompleteOrForeignCredentials() {
    for (String json :
        new String[] {
          "not json", "[]", "{\"host\":\"h\",\"dbname\":\"d\",\"username\":\"u\"}",
          "{\"jdbcUrl\":\"jdbc:mysql://h/d\",\"username\":\"u\",\"password\":\"p\"}"
        }) {
      var e = assertThrows(MigrationException.class, () -> DatabaseCredentials.parse(json));
      assertEquals(MigrationException.Code.CREDENTIALS_INVALID, e.code());
    }
  }

  @Test
  void neverRendersThePassword() {
    var credentials = new DatabaseCredentials("jdbc:postgresql://h/d", "u", "s3cret");
    assertFalse(credentials.toString().contains("s3cret"));
    var e =
        assertThrows(
            MigrationException.class, () -> DatabaseCredentials.parse("{\"password\":\"s3cret\" broken"));
    assertFalse(e.getMessage().contains("s3cret"));
  }
}
