package uk.gov.companieshouse.addresslookup.dbmigrate;

import com.amazonaws.services.lambda.runtime.Context;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class HandlerTest {

  private static final String SNAPSHOT_KEY = "address-lookup/db-schema-snapshots/address-lookup-db-1.4.0.zip";
  private static final String VERSION_ID = "v1";
  private static final String SHA256_HEX = "9f2c" + "0".repeat(60);

  @Mock private MigrationService migrationService;
  @Mock private Context lambdaContext;

  private Handler handler;

  @BeforeEach
  void setup() {
    handler = new Handler(migrationService);
  }

  private static Map<String, Object> buildEventPayload(String command) {
    return Map.of("command", command, "key", SNAPSHOT_KEY, "versionId", VERSION_ID, "sha256", SHA256_HEX);
  }

  @Test
  void validEventIsParsedAndTheServiceResultIsReturned() {
    var expectedRequest = new MigrationRequest(Command.INFO, SNAPSHOT_KEY, VERSION_ID, SHA256_HEX);
    Map<String, Object> serviceResult =
        Map.of("status", "SUCCESS", "schemaVersion", "2026.004", "pending", List.of("V2026.005__x.sql"));
    when(migrationService.handle(expectedRequest)).thenReturn(serviceResult);

    var result = handler.handleRequest(buildEventPayload("info"), lambdaContext);

    assertSame(serviceResult, result);
  }

  @Test
  void resultWithoutMigrationListsIsStillReturned() {
    var expectedRequest = new MigrationRequest(Command.MIGRATE, SNAPSHOT_KEY, VERSION_ID, SHA256_HEX);
    Map<String, Object> serviceResult = Map.of("status", "SUCCESS");
    when(migrationService.handle(expectedRequest)).thenReturn(serviceResult);

    var result = handler.handleRequest(buildEventPayload("migrate"), lambdaContext);

    assertEquals("SUCCESS", result.get("status"));
  }

  @Test
  void unknownCommandFailsAsInvalidInputWithoutCallingTheService() {
    var unknownCommandPayload = buildEventPayload("clean");
    var migrationException =
        assertThrows(
            MigrationException.class, () -> handler.handleRequest(unknownCommandPayload, lambdaContext));

    assertEquals(MigrationException.Code.INVALID_INPUT, migrationException.code());
    assertEquals("INVALID_INPUT: Unknown command: clean", migrationException.getMessage());
    verifyNoInteractions(migrationService);
  }

  @Test
  void unexpectedFieldFailsAsInvalidInputWithoutCallingTheService() {
    var eventPayloadWithExtraField =
        Map.<String, Object>of(
            "command", "migrate",
            "key", SNAPSHOT_KEY,
            "versionId", VERSION_ID,
            "sha256", SHA256_HEX,
            "locations", "filesystem:/seed");

    var migrationException =
        assertThrows(
            MigrationException.class, () -> handler.handleRequest(eventPayloadWithExtraField, lambdaContext));

    assertEquals(MigrationException.Code.INVALID_INPUT, migrationException.code());
    verifyNoInteractions(migrationService);
  }

  @Test
  void nullEventFailsAsInvalidInputWithoutCallingTheService() {
    var migrationException =
        assertThrows(MigrationException.class, () -> handler.handleRequest(null, lambdaContext));

    assertEquals(MigrationException.Code.INVALID_INPUT, migrationException.code());
    verifyNoInteractions(migrationService);
  }

  @Test
  void serviceFailureIsRethrownUnchanged() {
    var expectedRequest = new MigrationRequest(Command.MIGRATE, SNAPSHOT_KEY, VERSION_ID, SHA256_HEX);
    var serviceFailure = new MigrationException(MigrationException.Code.MIGRATION_FAILED, "boom");
    when(migrationService.handle(expectedRequest)).thenThrow(serviceFailure);

    var migrateEventPayload = buildEventPayload("migrate");
    var thrown =
        assertThrows(
            MigrationException.class, () -> handler.handleRequest(migrateEventPayload, lambdaContext));

    assertSame(serviceFailure, thrown);
  }
}

