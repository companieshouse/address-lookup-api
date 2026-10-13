package uk.gov.companieshouse.addresslookup.dbbootstrap;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

import java.io.IOException;
import java.net.URI;
import java.net.URLDecoder;
import java.nio.charset.StandardCharsets;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * One Parameter Store credentials document, as written by terraform/groups/aurora:
 * {@code {"jdbcUrl": "jdbc:postgresql://<writer>:5432/<db>?sslmode=verify-full&sslrootcert=...", "username": ..., "password": ...}}.
 * The {@code host}/{@code port}/{@code dbname} form from ALS-88 is also accepted. Never logged: {@link #toString()}
 * masks the password.
 */
public record DatabaseCredentials(String jdbcUrl, String username, String password) {

    private static final ObjectMapper JSON = new ObjectMapper();
    private static final String PREFIX = "jdbc:postgresql://";

    public DatabaseCredentials {
        require(jdbcUrl != null && jdbcUrl.startsWith(PREFIX), "jdbcUrl must start with " + PREFIX);
        require(username != null && !username.isBlank(), "username is empty");
        require(password != null && !password.isBlank(), "password is empty");
    }

    public static DatabaseCredentials fromJson(String json, String defaultRootCert) {
        JsonNode node;
        try {
            node = JSON.readTree(json);
        } catch (IOException e) {
            // The message could echo the secret, so it is not chained.
            throw new IllegalArgumentException("Credentials parameter is not valid JSON");
        }
        require(node != null && node.isObject(), "Credentials parameter is not a JSON object");
        String jdbcUrl = text(node, "jdbcUrl");
        if (jdbcUrl == null) {
            String host = text(node, "host");
            String dbname = text(node, "dbname");
            require(host != null && dbname != null, "Credentials need jdbcUrl, or host and dbname");
            int port = node.hasNonNull("port") ? node.get("port").asInt(5432) : 5432;
            jdbcUrl = PREFIX + host + ":" + port + "/" + dbname
                    + "?sslmode=verify-full&sslrootcert=" + defaultRootCert;
        }
        return new DatabaseCredentials(jdbcUrl, text(node, "username"), text(node, "password"));
    }

    public String host() {
        return uri().getHost();
    }

    public int port() {
        int port = uri().getPort();
        return port == -1 ? 5432 : port;
    }

    /** Query parameters of the JDBC URL, e.g. {@code sslmode} and {@code sslrootcert}. */
    public Map<String, String> parameters() {
        Map<String, String> parameters = new LinkedHashMap<>();
        String query = uri().getRawQuery();
        if (query == null) {
            return parameters;
        }
        for (String pair : query.split("&")) {
            int eq = pair.indexOf('=');
            String key = eq < 0 ? pair : pair.substring(0, eq);
            String value = eq < 0 ? "" : pair.substring(eq + 1);
            parameters.put(URLDecoder.decode(key, StandardCharsets.UTF_8), URLDecoder.decode(value, StandardCharsets.UTF_8));
        }
        return parameters;
    }

    private URI uri() {
        return URI.create(jdbcUrl.substring("jdbc:".length()));
    }

    private static String text(JsonNode node, String field) {
        JsonNode value = node.get(field);
        return value == null || value.isNull() ? null : value.asText();
    }

    static void require(boolean condition, String message) {
        if (!condition) {
            throw new IllegalArgumentException(message);
        }
    }

    @Override
    public String toString() {
        return "DatabaseCredentials[jdbcUrl=" + jdbcUrl + ", username=" + username + ", password=******]";
    }
}
