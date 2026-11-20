package uk.gov.companieshouse.addresslookup.lambda.shared.acquisition;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertSame;

import org.junit.jupiter.api.Test;

final class PermanentFailureExceptionTest {
  private static final String REASON_CODE = "CSV_NOT_FOUND";
  private static final String CAUSE_MESSAGE = "Primary CSV is missing";

  @Test
  void keepsTheReasonCode() {
    var failure = new PermanentFailureException(REASON_CODE, new Exception(CAUSE_MESSAGE));

    assertEquals(REASON_CODE, failure.getReasonCode());
  }

  @Test
  void messageStartsWithTheReasonCodeFollowedByTheCauseMessage() {
    var failure = new PermanentFailureException(REASON_CODE, new Exception(CAUSE_MESSAGE));

    assertEquals("CSV_NOT_FOUND: Primary CSV is missing", failure.getMessage());
  }

  @Test
  void keepsTheOriginalCause() {
    var cause = new Exception(CAUSE_MESSAGE);

    var failure = new PermanentFailureException(REASON_CODE, cause);

    assertSame(cause, failure.getCause());
  }
}

