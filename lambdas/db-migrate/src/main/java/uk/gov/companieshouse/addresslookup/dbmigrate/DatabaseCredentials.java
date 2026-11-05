package uk.gov.companieshouse.addresslookup.dbmigrate;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

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
    return new DatabaseCredentials(
        url,
        getTextValueFromJsonField(dbCredentialsJsonNode, "username"),
        getTextValueFromJsonField(dbCredentialsJsonNode, "password"));
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
