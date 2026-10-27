package uk.gov.companieshouse.addresslookup.lambda.shared.acquisition;

public class EventPublicationException extends RuntimeException {
  public EventPublicationException(String message) {
    super(message);
  }

  public EventPublicationException(String message, Throwable cause) {
    super(message, cause);
  }
}
