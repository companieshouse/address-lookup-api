package uk.gov.companieshouse.addresslookup.dbmigrate;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertSame;

import org.junit.jupiter.api.Test;

class MigrationExceptionTest {

  @Test
  void messageIsCodeThenColonThenDetail() {
    var e = new MigrationException(MigrationException.Code.INVALID_INPUT, "Unknown command: x");

    assertEquals("INVALID_INPUT: Unknown command: x", e.getMessage());
    assertEquals(MigrationException.Code.INVALID_INPUT, e.code());
  }

  @Test
  void everyCodeIsPrefixedToItsMessage() {
    for (var code : MigrationException.Code.values())
      assertEquals(code + ": detail", new MigrationException(code, "detail").getMessage());
  }

  @Test
  void causeIsKept() {
    var cause = new IllegalStateException("boom");

    var e = new MigrationException(MigrationException.Code.MIGRATION_FAILED, "failed", cause);

    assertSame(cause, e.getCause());
  }
}
