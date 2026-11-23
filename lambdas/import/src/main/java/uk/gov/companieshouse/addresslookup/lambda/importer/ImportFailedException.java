package uk.gov.companieshouse.addresslookup.lambda.importer;

public final class ImportFailedException extends RuntimeException {
  public ImportFailedException(Throwable cause) {
    super("import failed; durable state allows retry", cause);
  }
}
