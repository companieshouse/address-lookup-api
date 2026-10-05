package uk.gov.companieshouse.addresslookup.lambda.shared.runtime;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import uk.gov.companieshouse.release.model.ReleaseJson;

import java.security.MessageDigest;
import java.util.HexFormat;
import java.util.Map;

public final class RuntimeSupport {
    public static final ObjectMapper JSON = ReleaseJson.MAPPER;

    public static void check(boolean value, String message) {
        if (!value) throw new IllegalArgumentException(message);
    }

    public static String sha(byte[] bytes) throws Exception {
        return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(bytes));
    }

    public static JsonNode detail(Map<String, Object> e) {
        JsonNode jsonNode = JSON.valueToTree(e);
        return jsonNode.has("detail") ? jsonNode.get("detail") : jsonNode;
    }

    public static JsonNode detail(String e) {
        JsonNode jsonNode = JSON.valueToTree(e);
        return jsonNode.has("detail") ? jsonNode.get("detail") : jsonNode;
    }

    public static String required(JsonNode jsonNode, String field) {
        check(jsonNode.hasNonNull(field) && !jsonNode.get(field).asText().isBlank(), "Missing " + field);
        return jsonNode.get(field).asText();
    }
}
