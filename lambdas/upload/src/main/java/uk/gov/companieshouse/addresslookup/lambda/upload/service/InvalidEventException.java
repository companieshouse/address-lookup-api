package uk.gov.companieshouse.addresslookup.lambda.upload.service;

/**
 * The event itself is malformed or inconsistent, so redelivery can never succeed. Raised only for
 * validation of the event, never for unexpected failures in libraries or infrastructure.
 */
public final class InvalidEventException extends RuntimeException {
  public InvalidEventException(String message) {
    super(message);
  }

  public InvalidEventException(String message, Throwable cause) {
    super(message, cause);
  }
}
