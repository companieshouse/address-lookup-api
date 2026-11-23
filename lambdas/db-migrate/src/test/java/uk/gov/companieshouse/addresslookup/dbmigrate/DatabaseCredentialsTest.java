package uk.gov.companieshouse.addresslookup.dbmigrate;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

class DatabaseCredentialsTest {

  @Test
  void parsesHostPortShape() {
    var credentials =
        DatabaseCredentials.fromJson(
            """
            {"host":"db.cluster.local","port":5432,"dbname":"address_lookup",
             "username":"schema_owner","password":"s3cret"}\
            """);
    assertEquals(
        "jdbc:postgresql://db.cluster.local:5432/address_lookup?sslmode=require",
        credentials.jdbcUrl());
    assertEquals("schema_owner", credentials.username());
    assertEquals("s3cret", credentials.password());
  }

  @Test
  void prefersExplicitJdbcUrl() {
    var credentials =
        DatabaseCredentials.fromJson(
            """
            {"jdbcUrl":"jdbc:postgresql://h:6543/db?sslmode=verify-full","username":"u","password":"p"}\
            """);
    assertEquals("jdbc:postgresql://h:6543/db?sslmode=verify-full", credentials.jdbcUrl());
  }

  @ParameterizedTest
  @ValueSource(
      strings = {
        "not json",
        "[]",
        "{\"host\":\"h\",\"dbname\":\"d\",\"username\":\"u\"}",
        "{\"jdbcUrl\":\"jdbc:mysql://h/d\",\"username\":\"u\",\"password\":\"p\"}",
        "{\"jdbcUrl\":\"jdbc:postgresql://owner:s3cret@h/d\",\"username\":\"u\",\"password\":\"p\"}",
        "{\"jdbcUrl\":\"jdbc:postgresql://owner:pa/ss@h/d\",\"username\":\"u\",\"password\":\"p\"}",
        "{\"jdbcUrl\":\"jdbc:postgresql://owner:pa?ss@h/d\",\"username\":\"u\",\"password\":\"p\"}",
        "null",
        "\"text\"",
        "{\"host\":\"h\",\"dbname\":\"d\",\"username\":\" \",\"password\":\"p\"}",
        "{\"host\":\"h\",\"dbname\":\"d\",\"username\":\"u\",\"password\":123}",
        "{\"host\":\"h\",\"dbname\":\"d\",\"username\":\"u\",\"password\":\"\"}",
        "{\"host\":\"\",\"dbname\":\"d\",\"username\":\"u\",\"password\":\"p\"}",
        "{\"host\":\"h\",\"username\":\"u\",\"password\":\"p\"}"
      })
  void rejectsIncompleteBlankNonTextOrForeignCredentials(String dbCredentialsJson) {
    var migrationException =
        assertThrows(
            MigrationException.class, () -> DatabaseCredentials.fromJson(dbCredentialsJson));
    assertEquals(MigrationException.Code.CREDENTIALS_INVALID, migrationException.code());
  }

  @Test
  void sanitisesUserInfoAndPropertiesFromTheJdbcUrl() {
    var credentials =
        new DatabaseCredentials(
            "jdbc:postgresql://owner:s3cret@db.local:5432/address_lookup?user=owner&password=s3cret",
            "u",
            "p");

    assertEquals("jdbc:postgresql://db.local:5432/address_lookup", credentials.sanitisedJdbcUrl());
    assertFalse(credentials.toString().contains("s3cret"));
  }

  @Test
  void sanitisesAPasswordThatContainsAnAtSign() {
    var credentials =
        new DatabaseCredentials(
            "jdbc:postgresql://owner:prefix@secret@db.local:5432/address_lookup", "u", "p");

    assertEquals("jdbc:postgresql://db.local:5432/address_lookup", credentials.sanitisedJdbcUrl());
    assertFalse(credentials.toString().contains("secret"));
  }

  @Test
  void neverRendersThePassword() {
    var credentials = new DatabaseCredentials("jdbc:postgresql://h/d", "u", "s3cret");
    assertFalse(credentials.toString().contains("s3cret"));
    var migrationException =
        assertThrows(
            MigrationException.class,
            () -> DatabaseCredentials.fromJson("{\"password\":\"s3cret\" broken"));
    assertFalse(migrationException.getMessage().contains("s3cret"));
  }

  @Test
  void usesTheDefaultPortWhenThePortIsMissing() {
    var credentials =
        DatabaseCredentials.fromJson(
            "{\"host\":\"h\",\"dbname\":\"d\",\"username\":\"u\",\"password\":\"p\"}");

    assertEquals("jdbc:postgresql://h:5432/d?sslmode=require", credentials.jdbcUrl());
  }

  @Test
  void usesTheDefaultPortWhenThePortIsNotNumeric() {
    var credentials =
        DatabaseCredentials.fromJson(
            "{\"host\":\"h\",\"port\":\"abc\",\"dbname\":\"d\",\"username\":\"u\",\"password\":\"p\"}");

    assertEquals("jdbc:postgresql://h:5432/d?sslmode=require", credentials.jdbcUrl());
  }

  @Test
  void ignoresHostAndDbNameWhenAJdbcUrlIsGiven() {
    var credentials =
        DatabaseCredentials.fromJson(
            "{\"jdbcUrl\":\"jdbc:postgresql://explicit/db?sslmode=require\",\"host\":\"ignored\",\"username\":\"u\",\"password\":\"p\"}");

    assertEquals("jdbc:postgresql://explicit/db?sslmode=require", credentials.jdbcUrl());
  }

  @ParameterizedTest
  @ValueSource(
      strings = {
        "jdbc:postgresql://h/d?sslmode=require&user=other",
        "jdbc:postgresql://h/d?sslmode=require&password=other",
        "jdbc:postgresql://h/d?password&sslmode=require",
        "jdbc:postgresql://h/d?sslmode=require&PASSWORD=other",
        "jdbc:postgresql://h/d?sslmode=require&%70assword=other",
        "jdbc:postgresql://h/d?sslmode=require&+user=other"
      })
  void rejectsCredentialPropertiesInTheJdbcUrl(String jdbcUrl) {
    var credentialsJson = credentialsJsonWithUrl(jdbcUrl);

    var migrationException =
        assertThrows(MigrationException.class, () -> DatabaseCredentials.fromJson(credentialsJson));

    assertEquals(
        "CREDENTIALS_INVALID: jdbcUrl must not contain a username or password",
        migrationException.getMessage());
  }

  @ParameterizedTest
  @ValueSource(
      strings = {
        "jdbc:postgresql://h/d",
        "jdbc:postgresql://h/d?ApplicationName=x",
        "jdbc:postgresql://h/d?sslmode=disable",
        "jdbc:postgresql://h/d?sslmode=prefer",
        "jdbc:postgresql://h/d?sslmode=",
        "jdbc:postgresql://h/d?SSLMODE=require",
        "jdbc:postgresql://h/d?sslmode=require&sslmode=disable"
      })
  void rejectsAJdbcUrlThatDoesNotRequireTls(String jdbcUrl) {
    var credentialsJson = credentialsJsonWithUrl(jdbcUrl);

    var migrationException =
        assertThrows(MigrationException.class, () -> DatabaseCredentials.fromJson(credentialsJson));

    assertEquals(
        "CREDENTIALS_INVALID: jdbcUrl must set sslmode to require, verify-ca or verify-full",
        migrationException.getMessage());
  }

  @ParameterizedTest
  @ValueSource(strings = {"require", "verify-ca", "verify-full", "disable&sslmode=require"})
  void acceptsAnEncryptingSslMode(String sslMode) {
    var jdbcUrl = "jdbc:postgresql://h/d?sslmode=" + sslMode;

    assertEquals(jdbcUrl, DatabaseCredentials.fromJson(credentialsJsonWithUrl(jdbcUrl)).jdbcUrl());
  }

  @Test
  void reportsMalformedEncodingWithoutRevealingTheJdbcUrl() {
    var credentialsJson =
        credentialsJsonWithUrl("jdbc:postgresql://h/d?sslmode=require&x=s3cret%zz");

    var migrationException =
        assertThrows(MigrationException.class, () -> DatabaseCredentials.fromJson(credentialsJson));

    assertEquals(
        "CREDENTIALS_INVALID: jdbcUrl contains malformed percent-encoding",
        migrationException.getMessage());
    assertFalse(migrationException.getMessage().contains("s3cret"));
  }

  private static String credentialsJsonWithUrl(String jdbcUrl) {
    return "{\"jdbcUrl\":\"" + jdbcUrl + "\",\"username\":\"u\",\"password\":\"p\"}";
  }

  @Test
  void fallsBackToHostAndDbNameWhenTheJdbcUrlIsNull() {
    var credentials =
        DatabaseCredentials.fromJson(
            "{\"jdbcUrl\":null,\"host\":\"h\",\"dbname\":\"d\",\"username\":\"u\",\"password\":\"p\"}");

    assertEquals("jdbc:postgresql://h:5432/d?sslmode=require", credentials.jdbcUrl());
  }

  @Test
  void namesTheMissingFieldWithoutRevealingValues() {
    var migrationException =
        assertThrows(
            MigrationException.class,
            () ->
                DatabaseCredentials.fromJson(
                    "{\"host\":\"h\",\"dbname\":\"d\",\"password\":\"s3cret\"}"));

    assertEquals(
        "CREDENTIALS_INVALID: Database credentials are missing username",
        migrationException.getMessage());
  }
}
