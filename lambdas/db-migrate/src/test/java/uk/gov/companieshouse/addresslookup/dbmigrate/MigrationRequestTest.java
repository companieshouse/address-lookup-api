package uk.gov.companieshouse.addresslookup.dbmigrate;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import java.util.HashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

class MigrationRequestTest {

  private static final String SHA256_HEX = "9f2c" + "0".repeat(60);

  private static Map<String, Object> buildMigrationRequestPayload(Object... pairs) {
    var map = new HashMap<String, Object>();
    map.put("command", "migrate");
    map.put("key", "address-lookup/db-schema-snapshots/address-lookup-db-1.4.0.zip");
    map.put("versionId", "v1");
    map.put("sha256", SHA256_HEX);
    for (int i = 0; i < pairs.length; i += 2) {
      if (pairs[i + 1] == null) map.remove((String) pairs[i]);
      else map.put((String) pairs[i], pairs[i + 1]);
    }
    return map;
  }

  private static void assertInvalidInput(Map<String, Object> migrationRequestPayload) {
    var e = assertThrows(MigrationException.class, () -> MigrationRequest.parse(migrationRequestPayload));
    assertEquals(MigrationException.Code.INVALID_INPUT, e.code());
  }

  @Test
  void parsesMigrate() {
    var request = MigrationRequest.parse(buildMigrationRequestPayload());
    assertEquals(Command.MIGRATE, request.command());
    assertEquals("address-lookup/db-schema-snapshots/address-lookup-db-1.4.0.zip", request.key());
    assertEquals("v1", request.versionId());
    assertEquals(SHA256_HEX, request.sha256());
  }

  @ParameterizedTest
  @ValueSource(strings = {"migrate", "info", "validate", "repair"})
  void acceptsEveryCommand(String command) {
    assertEquals(command, MigrationRequest.parse(buildMigrationRequestPayload("command", command)).command().wireName());
  }

  @Test
  void normalisesTheChecksumToLowerCase() {
    assertEquals(SHA256_HEX, MigrationRequest.parse(buildMigrationRequestPayload("sha256", SHA256_HEX.toUpperCase())).sha256());
  }

  @ParameterizedTest
  @ValueSource(
      strings = {
        "latest.zip",
        "snapshots/x.zip",
        "address-lookup/db-schema-snapshots/../../x.zip",
        "address-lookup/db-schema-snapshots/a/b.zip",
        "address-lookup/db-schema-snapshots/x.tar",
        "changelogs/x.zip"
      })
  void rejectsKeysOutsideTheSnapshotPrefix(String key) {
    assertInvalidInput(buildMigrationRequestPayload("key", key));
  }

  @ParameterizedTest
  @ValueSource(strings = {"", "abc", "zz2c0000000000000000000000000000000000000000000000000000000000000"})
  void rejectsMalformedChecksums(String sha) {
    assertInvalidInput(buildMigrationRequestPayload("sha256", sha));
  }

  @Test
  void rejectsMissingFieldsUnknownCommandsAndUnknownFields() {
    assertInvalidInput(buildMigrationRequestPayload("sha256", null));
    assertInvalidInput(buildMigrationRequestPayload("versionId", null));
    assertInvalidInput(buildMigrationRequestPayload("versionId", " "));
    assertInvalidInput(buildMigrationRequestPayload("command", "clean"));
    assertInvalidInput(buildMigrationRequestPayload("command", "undo"));
    assertInvalidInput(buildMigrationRequestPayload("locations", "filesystem:/seed"));
    assertInvalidInput(buildMigrationRequestPayload("key", 42));
  }

  @Test
  void rejectsANullPayload() {
    assertInvalidInput(null);
  }

  @Test
  void rejectsFieldsThatAreNotStrings() {
    assertInvalidInput(buildMigrationRequestPayload("command", 1));
    assertInvalidInput(buildMigrationRequestPayload("versionId", 7));
    assertInvalidInput(buildMigrationRequestPayload("sha256", List.of("abc")));
  }

  @Test
  void rejectsAMissingCommandAndAMissingKey() {
    assertInvalidInput(buildMigrationRequestPayload("command", null));
    assertInvalidInput(buildMigrationRequestPayload("key", null));
  }

  @Test
  void acceptsAVersionIdOfExactlyTheMaximumLength() {
    var versionId = "v".repeat(1024);

    assertEquals(versionId, MigrationRequest.parse(buildMigrationRequestPayload("versionId", versionId)).versionId());
  }

  @Test
  void rejectsAVersionIdLongerThanTheMaximum() {
    assertInvalidInput(buildMigrationRequestPayload("versionId", "v".repeat(1025)));
  }

  @Test
  void acceptsAKeyNameOfExactlyTheMaximumLength() {
    var key = "address-lookup/db-schema-snapshots/" + "a".repeat(200) + ".zip";

    assertEquals(key, MigrationRequest.parse(buildMigrationRequestPayload("key", key)).key());
  }

  @Test
  void rejectsAKeyNameLongerThanTheMaximum() {
    assertInvalidInput(
        buildMigrationRequestPayload("key", "address-lookup/db-schema-snapshots/" + "a".repeat(201) + ".zip"));
  }

  @Test
  void errorMessageNamesTheOffendingField() {
    var e = assertThrows(MigrationException.class, () -> MigrationRequest.parse(buildMigrationRequestPayload("locations", "x")));

    assertEquals("INVALID_INPUT: Unexpected field: locations", e.getMessage());
  }
}
