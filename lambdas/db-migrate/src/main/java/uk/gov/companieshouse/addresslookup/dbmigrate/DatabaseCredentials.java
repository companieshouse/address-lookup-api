package uk.gov.companieshouse.addresslookup.dbmigrate;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

import java.net.URLDecoder;
import java.nio.charset.StandardCharsets;
import java.util.Locale;
import java.util.Set;
import java.util.regex.Pattern;

import static uk.gov.companieshouse.addresslookup.dbmigrate.MigrationException.Code.CREDENTIALS_INVALID;

/**
 * Schema-owner credentials, as JSON in the Parameter Store parameter. Accepts the RDS secret
 * shape ({@code host, port, dbname, username, password}) or an explicit {@code jdbcUrl}.
 */
public record DatabaseCredentials(String jdbcUrl, String username, String password) {
  private static final ObjectMapper JSON = new ObjectMapper();
  /** The {@code user:password@} part of {@code jdbc:postgresql://user:password@host/db}. */
  private static final Pattern USER_INFO = Pattern.compile("//[^/]*@");
  private static final int DEFAULT_POSTGRES_PORT = 5432;
  /** The driver lets these URL properties override the username and password passed to it. */
  private static final Set<String> CREDENTIAL_PROPERTIES = Set.of("user", "password");
  private static final String SSL_MODE_PROPERTY = "sslmode";
  /** Without one of these the driver may connect without TLS. */
  private static final Set<String> ENCRYPTED_SSL_MODES = Set.of("require", "verify-ca", "verify-full");

  public static DatabaseCredentials fromJson(String dbCredentialsJson) {
    JsonNode dbCredentialsJsonNode;
    try {
      dbCredentialsJsonNode = JSON.readTree(dbCredentialsJson);
    } catch (Exception e) {
      // Never include the credentials JSON in the error.
      throw new MigrationException(CREDENTIALS_INVALID, "Database credentials are not valid JSON");
    }
    if (dbCredentialsJsonNode == null || !dbCredentialsJsonNode.isObject())
      throw new MigrationException(CREDENTIALS_INVALID, "Database credentials must be a JSON object");
    String url =
        dbCredentialsJsonNode.hasNonNull("jdbcUrl")
            ? getTextValueFromJsonField(dbCredentialsJsonNode, "jdbcUrl")
            : "jdbc:postgresql://%s:%d/%s?sslmode=require"
                .formatted(
                    getTextValueFromJsonField(dbCredentialsJsonNode, "host"),
                    dbCredentialsJsonNode.path("port").asInt(DEFAULT_POSTGRES_PORT),
                    getTextValueFromJsonField(dbCredentialsJsonNode, "dbname"));
    if (!url.startsWith("jdbc:postgresql://"))
      throw new MigrationException(CREDENTIALS_INVALID, "jdbcUrl must be a PostgreSQL JDBC URL");
    // The username and password have their own fields, so they must not be in the URL.
    if (url.contains("@"))
      throw new MigrationException(CREDENTIALS_INVALID, "jdbcUrl must not contain a username or password");
    validateUrlProperties(url);
    return new DatabaseCredentials(
        url,
        getTextValueFromJsonField(dbCredentialsJsonNode, "username"),
        getTextValueFromJsonField(dbCredentialsJsonNode, "password"));
  }

  /**
   * Checks the connection properties after {@code ?}, for example {@code ?sslmode=require}. Rejects
   * {@code user} or {@code password} in any letter case or percent-encoding, and requires an
   * encrypting {@code sslmode}. The driver uses the last {@code sslmode} given, so this does too.
   *
   * @throws MigrationException CREDENTIALS_INVALID, without the URL, which may contain a secret
   */
  private static void validateUrlProperties(String url) {
    int propertiesStart = url.indexOf('?');
    String sslMode = null;
    if (propertiesStart >= 0) {
      for (String property : url.substring(propertiesStart + 1).split("&")) {
        String[] nameAndValue = property.split("=", 2);
        String name = decodeUrlComponent(nameAndValue[0]);
        String value = nameAndValue.length > 1 ? decodeUrlComponent(nameAndValue[1]) : "";
        if (CREDENTIAL_PROPERTIES.contains(name.strip().toLowerCase(Locale.ROOT)))
          throw new MigrationException(CREDENTIALS_INVALID, "jdbcUrl must not contain a username or password");
        if (nameAndValue[0].equals(SSL_MODE_PROPERTY)) sslMode = value;
      }
    }
    if (sslMode == null || !ENCRYPTED_SSL_MODES.contains(sslMode))
      throw new MigrationException(
          CREDENTIALS_INVALID, "jdbcUrl must set sslmode to require, verify-ca or verify-full");
  }

  private static String decodeUrlComponent(String component) {
    try {
      return URLDecoder.decode(component, StandardCharsets.UTF_8);
    } catch (IllegalArgumentException e) {
      // The decoder message quotes the input, which may contain a secret.
      throw new MigrationException(CREDENTIALS_INVALID, "jdbcUrl contains malformed percent-encoding");
    }
  }

  /**
   * Returns the text value of {@code field}, for example {@code "admin"} for {@code {"username": "admin"}}.
   *
   * @throws MigrationException CREDENTIALS_INVALID if the field is missing, not text or blank
   */
  private static String getTextValueFromJsonField(JsonNode dbCredentialsJsonNode, String field) {
    if (!dbCredentialsJsonNode.path(field).isTextual() || dbCredentialsJsonNode.path(field).asText().isBlank())
      throw new MigrationException(CREDENTIALS_INVALID, "Database credentials are missing " + field);
    return dbCredentialsJsonNode.path(field).asText();
  }

  /**
   * The JDBC URL without user info or connection properties (everything after {@code ?}), so it
   * is safe to put in logs and error messages even if the stored URL embeds a password.
   */
  public String sanitisedJdbcUrl() {
    String withoutProperties = jdbcUrl.split("\\?", 2)[0];
    return USER_INFO.matcher(withoutProperties).replaceFirst("//");
  }

  @Override
  public String toString() {
    // The secret is deliberately left out of this text.
    return "DatabaseCredentials[jdbcUrl=" + sanitisedJdbcUrl() + ", username=" + username + "]";
  }
}
