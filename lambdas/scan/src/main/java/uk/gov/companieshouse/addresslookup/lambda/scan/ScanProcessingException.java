package uk.gov.companieshouse.addresslookup.lambda.scan;

public final class ScanProcessingException extends RuntimeException {
  public ScanProcessingException(Throwable cause) {
    super("scan failed; PutEvents failures are retried", cause);
  }
}
