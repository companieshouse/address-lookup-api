package uk.gov.companieshouse.addresslookup.lamda.shared.runtime;

import com.fasterxml.jackson.databind.*;

import java.util.*;
import java.security.*;
import java.sql.Connection;
import java.sql.DriverManager;

public final class RuntimeSupport {
    public static final ObjectMapper JSON = new ObjectMapper();

    public static String env(String key) {
        String v = System.getenv(key);
        if (v == null || v.isBlank()) throw new IllegalArgumentException("Missing " + key);
        return v;
    }

    public static void check(boolean value, String message) {
        if (!value) throw new IllegalArgumentException(message);
    }

    public static String sha(byte[] bytes) throws Exception {
        return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(bytes));
    }

    public static Connection connect() throws Exception {
        Connection connection = DriverManager.getConnection(
                env("SPRING_DATASOURCE_URL"),
                env("SPRING_DATASOURCE_USERNAME"),
                env("SPRING_DATASOURCE_PASSWORD"));
        connection.setAutoCommit(false);
        return connection;
    }

    public static JsonNode detail(Map<String, Object> e) {
        JsonNode n = JSON.valueToTree(e);
        return n.has("detail") ? n.get("detail") : n;
    }

    public static String required(JsonNode n, String field) {
        check(n.hasNonNull(field) && !n.get(field).asText().isBlank(), "Missing " + field);
        return n.get(field).asText();
    }
}
