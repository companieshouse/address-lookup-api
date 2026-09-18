package uk.gov.companieshouse.addresslookup.lamda.shared.runtime;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

import java.security.MessageDigest;
import java.util.HexFormat;
import java.util.Map;

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

    public static JsonNode detail(Map<String, Object> e) {
        JsonNode n = JSON.valueToTree(e);
        return n.has("detail") ? n.get("detail") : n;
    }

    public static String required(JsonNode n, String field) {
        check(n.hasNonNull(field) && !n.get(field).asText().isBlank(), "Missing " + field);
        return n.get(field).asText();
    }
}
