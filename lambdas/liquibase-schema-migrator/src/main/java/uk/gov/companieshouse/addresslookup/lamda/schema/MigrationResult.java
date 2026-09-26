package uk.gov.companieshouse.addresslookup.lamda.schema;

import com.fasterxml.jackson.annotation.JsonInclude;

import java.util.List;

/** The invocation response, which the pipeline checks and prints to the build log as the audit record. */
@JsonInclude(JsonInclude.Include.NON_NULL)
public record MigrationResult(
        Status status,
        MigrationMode mode,
        String version,
        String tag,
        List<String> pendingChangeSets,
        List<String> appliedChangeSets,
        String postgisVersion,
        String sql) {

    public enum Status {
        /** VALIDATE: the changelog is consistent with the database; pendingChangeSets and sql describe UPDATE. */
        VALIDATED,
        /** UPDATE: every pending changeset has been applied and the database tagged. */
        COMPLETE,
        /** UPDATE: time ran out with changesets still pending. Invoke UPDATE again to resume. */
        PARTIAL,
        /** STATUS: nothing is pending. */
        UP_TO_DATE,
        /** STATUS: changesets are still pending. */
        PENDING,
        /** RELEASE_LOCKS: the Liquibase lock has been cleared. */
        LOCKS_RELEASED
    }
}
