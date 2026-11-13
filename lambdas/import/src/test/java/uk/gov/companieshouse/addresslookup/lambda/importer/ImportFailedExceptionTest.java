package uk.gov.companieshouse.addresslookup.lambda.importer;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertSame;

import org.junit.jupiter.api.Test;

class ImportFailedExceptionTest {

  @Test
  void keepsCauseAndRetryMessage() {
    var cause = new IllegalArgumentException("Import can't find source CVS.");

    var exception = new ImportFailedException(cause);

    assertEquals("import failed; durable state allows retry", exception.getMessage());
    assertSame(cause, exception.getCause());
  }
}
