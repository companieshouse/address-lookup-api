package uk.gov.companieshouse.addresslookup.lambda.shared.acquisition;

public final class EventPublicationException extends RuntimeException {
  public EventPublicationException(String message) {
    super(message);
  }
}
