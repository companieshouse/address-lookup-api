package uk.gov.companieshouse.addresslookup.dbmigrate;

import java.util.Arrays;

import static uk.gov.companieshouse.addresslookup.dbmigrate.MigrationException.Code.INVALID_INPUT;

/** Flyway operations the pipeline may request. Names match the Flyway CLI. */
public enum Command {
  /** Apply every pending migration. */
  MIGRATE("migrate"),
  /** Report the current schema version and the pending migrations. Changes nothing. */
  INFO("info"),
  /** Check every applied migration still matches its script. Changes nothing. */
  VALIDATE("validate"),
  /**
   * Break-glass only: remove failed entries from the schema history and realign checksums.
   * Disabled unless ALLOW_REPAIR=true.
   */
  REPAIR("repair");

  private final String wireName;

  Command(String wireName) {
    this.wireName = wireName;
  }

  public String wireName() {
    return wireName;
  }

  public boolean changesDatabase() {
    return this == MIGRATE || this == REPAIR;
  }

  static Command parse(String value) {
    return Arrays.stream(values())
        .filter(c -> c.wireName.equals(value))
        .findFirst()
        .orElseThrow(() -> new MigrationException(INVALID_INPUT, "Unknown command: " + value));
  }
}
