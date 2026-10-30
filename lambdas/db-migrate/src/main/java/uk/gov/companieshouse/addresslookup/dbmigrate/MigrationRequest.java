package uk.gov.companieshouse.addresslookup.dbmigrate;

import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.regex.Pattern;

import static uk.gov.companieshouse.addresslookup.dbmigrate.MigrationException.Code.INVALID_INPUT;

/**
 * Invocation payload, for example:
 *
 * <pre>{"command":"migrate","key":"address-lookup/db-schema-snapshots/address-lookup-db-1.4.0.zip","versionId":"…","sha256":"…"}</pre>
 *
 * The snapshot is pinned to an exact S3 object version, and its bytes must match the SHA-256
 * recorded when the pipeline gathered it.
 */
public record MigrationRequest(Command command, String key, String versionId, String sha256) {

  private static final Set<String> FIELDS = Set.of("command", "key", "versionId", "sha256");
  private static final Pattern KEY = Pattern.compile("address-lookup/db-schema-snapshots/[A-Za-z0-9._-]{1,200}\\.zip");
  private static final Pattern SHA256 = Pattern.compile("[0-9a-f]{64}");

  public MigrationRequest {
    require(command != null, "command is required");
    require(key != null && KEY.matcher(key).matches(), "key must match address-lookup/db-schema-snapshots/<name>.zip");
    require(
        versionId != null && !versionId.isBlank() && versionId.length() <= 1024,
        "versionId is required");
    require(sha256 != null, "sha256 is required");
    sha256 = sha256.toLowerCase(Locale.ROOT);
    require(SHA256.matcher(sha256).matches(), "sha256 must be 64 hex characters");
  }

  public static MigrationRequest parse(Map<String, Object> event) {
    require(event != null, "payload is required");
    for (String field : event.keySet())
      require(FIELDS.contains(field), "Unexpected field: " + field);
    return new MigrationRequest(
        Command.parse(text(event, "command")),
        text(event, "key"),
        text(event, "versionId"),
        text(event, "sha256"));
  }

  private static String text(Map<String, Object> event, String field) {
    Object value = event.get(field);
    require(value instanceof String, field + " must be a string");
    return (String) value;
  }

  private static void require(boolean ok, String message) {
    if (!ok) throw new MigrationException(INVALID_INPUT, message);
  }
}
