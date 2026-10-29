package uk.gov.companieshouse.addresslookup.dbmigrate;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import java.util.HashMap;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

class MigrationRequestTest {

  private static final String SHA = "9f2c" + "0".repeat(60);

  private static Map<String, Object> payload(Object... pairs) {
    var map = new HashMap<String, Object>();
    map.put("command", "migrate");
    map.put("key", "address-lookup-api/db-schema-snapshots/address-lookup-db-1.4.0.zip");
    map.put("versionId", "v1");
    map.put("sha256", SHA);
    for (int i = 0; i < pairs.length; i += 2) {
      if (pairs[i + 1] == null) map.remove((String) pairs[i]);
      else map.put((String) pairs[i], pairs[i + 1]);
    }
    return map;
  }

  private static void assertInvalid(Map<String, Object> payload) {
    var e = assertThrows(MigrationException.class, () -> MigrationRequest.parse(payload));
    assertEquals(MigrationException.Code.INVALID_INPUT, e.code());
  }

  @Test
  void parsesMigrate() {
    var request = MigrationRequest.parse(payload());
    assertEquals(Command.MIGRATE, request.command());
    assertEquals("address-lookup-api/db-schema-snapshots/address-lookup-db-1.4.0.zip", request.key());
    assertEquals("v1", request.versionId());
    assertEquals(SHA, request.sha256());
  }

  @ParameterizedTest
  @ValueSource(strings = {"migrate", "info", "validate", "repair"})
  void acceptsEveryCommand(String command) {
    assertEquals(command, MigrationRequest.parse(payload("command", command)).command().wireName());
  }

  @Test
  void normalisesTheChecksumToLowerCase() {
    assertEquals(SHA, MigrationRequest.parse(payload("sha256", SHA.toUpperCase())).sha256());
  }

  @ParameterizedTest
  @ValueSource(
      strings = {
        "latest.zip",
        "address-lookup-api/db-schema-snapshots/../x.zip",
        "address-lookup-api/db-schema-snapshots/a/b.zip",
        "address-lookup-api/db-schema-snapshots/x.tar",
        "snapshots/x.zip",
        "changelogs/x.zip"
      })
  void rejectsKeysOutsideTheSnapshotPrefix(String key) {
    assertInvalid(payload("key", key));
  }

  @ParameterizedTest
  @ValueSource(strings = {"", "abc", "zz2c0000000000000000000000000000000000000000000000000000000000000"})
  void rejectsMalformedChecksums(String sha) {
    assertInvalid(payload("sha256", sha));
  }

  @Test
  void rejectsMissingFieldsUnknownCommandsAndUnknownFields() {
    assertInvalid(payload("sha256", null));
    assertInvalid(payload("versionId", null));
    assertInvalid(payload("versionId", " "));
    assertInvalid(payload("command", "clean"));
    assertInvalid(payload("command", "undo"));
    assertInvalid(payload("locations", "filesystem:/seed"));
    assertInvalid(payload("key", 42));
  }
}
