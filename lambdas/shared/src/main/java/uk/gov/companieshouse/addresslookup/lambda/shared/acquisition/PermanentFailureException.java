package uk.gov.companieshouse.addresslookup.lambda.shared.acquisition;

/**
 * A failure that will not succeed on a retry with the same input. The reason code tells an operator
 * what to check.
 */
public class PermanentFailureException extends RuntimeException {
  private final String reasonCode;

  public PermanentFailureException(String reasonCode, Throwable cause) {
    super(reasonCode + ": " + cause.getMessage(), cause);
    this.reasonCode = reasonCode;
  }

  public String getReasonCode() {
    return reasonCode;
  }
}

