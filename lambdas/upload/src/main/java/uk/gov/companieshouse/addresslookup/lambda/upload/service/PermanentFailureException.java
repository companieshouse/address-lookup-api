package uk.gov.companieshouse.addresslookup.lambda.upload.service;

/**
 * A failure that redelivery can never fix. The upload is rejected so quarantine can block the
 * release, instead of retrying the message until it is dead-lettered. The message must not contain
 * CSV values.
 */
public final class PermanentFailureException extends RuntimeException {
  public static final String HEADER_MISMATCH = "HEADER_MISMATCH";
  public static final String SCHEMA_VERSION_MISMATCH = "SCHEMA_VERSION_MISMATCH";
  public static final String DATASET_NOT_IN_RELEASE = "DATASET_NOT_IN_RELEASE";
  public static final String MANIFEST_INVALID = "MANIFEST_INVALID";
  public static final String MANIFEST_CONFLICT = "MANIFEST_CONFLICT";
  public static final String ROW_COUNT_MISMATCH = "ROW_COUNT_MISMATCH";
  public static final String INVALID_CSV_DATA = "INVALID_CSV_DATA";
  public static final String SCAN_NOT_CLEAN = "SCAN_NOT_CLEAN";
  public static final String INVALID_EVENT = "INVALID_EVENT";

  private final String code;

  public PermanentFailureException(String code, String message) {
    super(message);
    this.code = code;
  }

  public String code() {
    return code;
  }
}
