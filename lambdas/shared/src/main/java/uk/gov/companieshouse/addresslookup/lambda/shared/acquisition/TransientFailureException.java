package uk.gov.companieshouse.addresslookup.lambda.shared.acquisition;

/**
 * A failure that may succeed on the next run. The reason code tells an operator what to check.
 */
public class TransientFailureException extends RuntimeException {
  private final String reasonCode;

  public TransientFailureException(String reasonCode, Throwable cause) {
    super(reasonCode + ": " + cause.getMessage(), cause);
    this.reasonCode = reasonCode;
  }

  public String getReasonCode() {
    return reasonCode;
  }
}

