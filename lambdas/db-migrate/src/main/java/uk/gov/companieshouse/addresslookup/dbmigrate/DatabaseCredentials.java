package uk.gov.companieshouse.addresslookup.dbmigrate;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

import static uk.gov.companieshouse.addresslookup.dbmigrate.MigrationException.Code.CREDENTIALS_INVALID;

/**
 * Schema-owner credentials, as JSON in the Parameter Store parameter. Accepts the RDS secret
 * shape ({@code host, port, dbname, username, password}) or an explicit {@code jdbcUrl}.
 */
public record DatabaseCredentials(String jdbcUrl, String username, String password) {
  private static final ObjectMapper JSON = new ObjectMapper();

  public static DatabaseCredentials parse(String value) {
    JsonNode node;
    try {
      node = JSON.readTree(value);
    } catch (Exception e) {
      // Never include the parameter value in the error.
      throw new MigrationException(CREDENTIALS_INVALID, "Database credentials are not valid JSON");
    }
    if (node == null || !node.isObject())
      throw new MigrationException(CREDENTIALS_INVALID, "Database credentials must be a JSON object");
    String url =
        node.hasNonNull("jdbcUrl")
            ? text(node, "jdbcUrl")
            : "jdbc:postgresql://%s:%d/%s?sslmode=require"
                .formatted(
                    text(node, "host"), node.path("port").asInt(5432), text(node, "dbname"));
    if (!url.startsWith("jdbc:postgresql://"))
      throw new MigrationException(CREDENTIALS_INVALID, "jdbcUrl must be a PostgreSQL JDBC URL");
    return new DatabaseCredentials(url, text(node, "username"), text(node, "password"));
  }

  private static String text(JsonNode node, String field) {
    if (!node.path(field).isTextual() || node.path(field).asText().isBlank())
      throw new MigrationException(CREDENTIALS_INVALID, "Database credentials are missing " + field);
    return node.path(field).asText();
  }

  @Override
  public String toString() {
    return "DatabaseCredentials[jdbcUrl=" + jdbcUrl + ", username=" + username + ", password=***]";
  }
}
