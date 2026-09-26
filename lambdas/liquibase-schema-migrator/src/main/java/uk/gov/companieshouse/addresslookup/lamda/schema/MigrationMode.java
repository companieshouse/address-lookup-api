package uk.gov.companieshouse.addresslookup.lamda.schema;

/** What an invocation of the schema migrator is asked to do. */
public enum MigrationMode {
    /** Check the changelog against the database and report, as SQL, what UPDATE would apply. Changes nothing. */
    VALIDATE,
    /** Apply pending changesets, one at a time, until none remain or the invocation runs low on time. */
    UPDATE,
    /** Report whether any changeset is still pending. Used to verify an UPDATE. */
    STATUS,
    /** Break-glass: clear a DATABASECHANGELOGLOCK left behind by an invocation that was killed mid-update. */
    RELEASE_LOCKS
}
