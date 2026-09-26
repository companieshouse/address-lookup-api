package uk.gov.companieshouse.addresslookup.lamda.schema;

import java.util.Locale;
import java.util.Map;
import java.util.regex.Pattern;

/**
 * The invocation payload. It names a released changelog but never where it lives: the bucket and key prefix are
 * fixed by Terraform, so a caller can only choose between artefacts the pipeline has already published, and the
 * SHA-256 pins the exact bytes that the pipeline released.
 */
public record MigrationRequest(MigrationMode mode, String version, String sha256) {

    private static final Pattern VERSION = Pattern.compile("^[0-9]{1,3}\\.[0-9]{1,3}\\.[0-9]{1,3}$");
    private static final Pattern SHA256 = Pattern.compile("^[0-9a-f]{64}$");

    public MigrationRequest {
        require(mode != null, "mode is required");
        require(version != null && VERSION.matcher(version).matches(), "version must be a semantic version X.Y.Z");
        require(sha256 != null && SHA256.matcher(sha256).matches(), "sha256 must be 64 lower case hex characters");
    }

    public static MigrationRequest from(Map<String, Object> event) {
        require(event != null, "event is required");
        return new MigrationRequest(mode(event.get("mode")), text(event.get("version")), text(event.get("sha256")));
    }

    private static MigrationMode mode(Object value) {
        String mode = text(value);
        require(mode != null, "mode is required");
        try {
            return MigrationMode.valueOf(mode.toUpperCase(Locale.ROOT));
        } catch (IllegalArgumentException e) {
            throw new IllegalArgumentException("Unsupported mode: " + mode);
        }
    }

    private static String text(Object value) {
        return value == null ? null : value.toString().trim();
    }

    static void require(boolean condition, String message) {
        if (!condition) {
            throw new IllegalArgumentException(message);
        }
    }
}
