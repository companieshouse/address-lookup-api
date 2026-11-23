package uk.gov.companieshouse.addresslookup.lambda.download;

public final class DownloadFailedException extends RuntimeException {
  public DownloadFailedException(Throwable cause) {
    super("download failed; durable state allows retry", cause);
  }
}
