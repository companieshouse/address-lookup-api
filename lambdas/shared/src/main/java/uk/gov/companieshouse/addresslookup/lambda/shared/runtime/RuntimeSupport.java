package uk.gov.companieshouse.addresslookup.lambda.shared.runtime;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.io.IOException;
import java.util.function.Supplier;
import uk.gov.companieshouse.release.model.ReleaseJson;

import java.net.URI;
import java.security.MessageDigest;
import java.util.HexFormat;
import java.util.Map;

public final class RuntimeSupport {
    public static final ObjectMapper JSON = ReleaseJson.MAPPER;

    public static void check(boolean value, String message) {
        if (!value) throw new IllegalArgumentException(message);
    }

    public static <E extends Exception> void check(boolean value, Supplier<E> exceptionSupplier) throws E {
        if (!value) throw exceptionSupplier.get();
    }

    public static String sha(byte[] bytes) throws Exception {
        return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(bytes));
    }

    public static JsonNode detail(Map<String, Object> e) {
        JsonNode n = JSON.valueToTree(e);
        if (!n.has("detail")) return n;
        JsonNode detail = n.get("detail");
        if (!detail.isTextual()) return detail;
        try {
            return JSON.readTree(detail.asText());
        } catch (IOException ex) {
            throw new IllegalArgumentException("Invalid event detail", ex);
        }
    }

    public static JsonNode detail(String e) throws IOException {
        return detailNode(JSON.readTree(e));
    }

    private static JsonNode detailNode(JsonNode node) {
        return node.has("detail") ? node.get("detail") : node;
    }

    public static String required(JsonNode jsonNode, String field) {
        check(jsonNode.hasNonNull(field) && !jsonNode.get(field).asText().isBlank(), "Missing " + field);
        return jsonNode.get(field).asText();
    }

    public static String sanitizeUrl(String value) {
        URI uri = URI.create(value);
        return uri.getScheme() + "://" + uri.getHost()
                + (uri.getRawPath() == null ? "" : uri.getRawPath())
                + (uri.getRawQuery() == null ? "" : "?***")
                + (uri.getRawFragment() == null ? "" : "#" + uri.getRawFragment());
    }
}
