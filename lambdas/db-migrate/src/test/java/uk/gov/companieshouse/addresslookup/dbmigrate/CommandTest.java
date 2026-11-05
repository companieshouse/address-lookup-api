package uk.gov.companieshouse.addresslookup.dbmigrate;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.NullSource;
import org.junit.jupiter.params.provider.ValueSource;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class CommandTest {

  @ParameterizedTest
  @CsvSource({"migrate,MIGRATE", "info,INFO", "validate,VALIDATE", "repair,REPAIR"})
  void parsesEveryWireName(String wireName, Command expectedCommand) {
    assertEquals(expectedCommand, Command.parse(wireName));
  }

  @ParameterizedTest
  @CsvSource({"MIGRATE,migrate", "INFO,info", "VALIDATE,validate", "REPAIR,repair"})
  void wireNameIsLowerCase(Command command, String expectedWireName) {
    assertEquals(expectedWireName, command.wireName());
  }

  @ParameterizedTest
  @ValueSource(strings = {"MIGRATE", "Migrate", "clean", "undo", "", " migrate"})
  @NullSource
  void rejectsUnknownCommandsAsInvalidInput(String value) {
    var e = assertThrows(MigrationException.class, () -> Command.parse(value));

    assertEquals(MigrationException.Code.INVALID_INPUT, e.code());
    assertEquals("INVALID_INPUT: Unknown command: " + value, e.getMessage());
  }

  @Test
  void onlyMigrateAndRepairChangeTheDatabase() {
    assertTrue(Command.MIGRATE.changesDatabase());
    assertTrue(Command.REPAIR.changesDatabase());
    assertFalse(Command.INFO.changesDatabase());
    assertFalse(Command.VALIDATE.changesDatabase());
  }
}

