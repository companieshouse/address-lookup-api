package uk.gov.companieshouse.addresslookup.lambda.scan;

public enum InvalidReason {
  UNEXPECTED_SOURCE_OR_DETAIL_TYPE("unexpected source or detail type"),
  INVALID_OBJECT_KEY("invalid object key"),
  MISSING_VERSION_ID("missing versionId"),
  UNKNOWN_BUCKET_FOR_OBJECT_KEY("unknown bucket for object key");

  private final String message;

  InvalidReason(String message) {
    this.message = message;
  }

  public String message() {
    return message;
  }
}
