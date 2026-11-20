package uk.gov.companieshouse.addresslookup.lambda.shared.acquisition;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertSame;

import org.junit.jupiter.api.Test;

final class TransientFailureExceptionTest {
  private static final String REASON_CODE = "CHECKSUM_MISMATCH";
  private static final String CAUSE_MESSAGE = "Expected checksum differs from actual checksum";

  @Test
  void keepsTheReasonCode() {
    var failure = new TransientFailureException(REASON_CODE, new Exception(CAUSE_MESSAGE));

    assertEquals(REASON_CODE, failure.getReasonCode());
  }

  @Test
  void messageStartsWithTheReasonCodeFollowedByTheCauseMessage() {
    var failure = new TransientFailureException(REASON_CODE, new Exception(CAUSE_MESSAGE));

    assertEquals(
        "CHECKSUM_MISMATCH: Expected checksum differs from actual checksum", failure.getMessage());
  }

  @Test
  void keepsTheOriginalCause() {
    var cause = new Exception(CAUSE_MESSAGE);

    var failure = new TransientFailureException(REASON_CODE, cause);

    assertSame(cause, failure.getCause());
  }
}

