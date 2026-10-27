package uk.gov.companieshouse.addresslookup.dbmigrate;

/** A failure with a stable code; the pipeline log shows {@code CODE: message}. */
public final class MigrationException extends RuntimeException {
  public enum Code {
    INVALID_INPUT,
    BUNDLE_NOT_FOUND,
    BUNDLE_INVALID,
    BUNDLE_CHECKSUM_MISMATCH,
    BUNDLE_TOO_LARGE,
    MIGRATIONS_NOT_FOUND,
    CREDENTIALS_INVALID,
    DATABASE_UNAVAILABLE,
    REPAIR_DISABLED,
    VALIDATION_FAILED,
    MIGRATION_FAILED
  }

  private final Code code;

  public MigrationException(Code code, String message) {
    this(code, message, null);
  }

  public MigrationException(Code code, String message, Throwable cause) {
    super(code + ": " + message, cause);
    this.code = code;
  }

  public Code code() {
    return code;
  }
}
