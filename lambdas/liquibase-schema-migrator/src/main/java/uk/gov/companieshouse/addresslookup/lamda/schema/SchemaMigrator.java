package uk.gov.companieshouse.addresslookup.lamda.schema;

import liquibase.Contexts;
import liquibase.LabelExpression;
import liquibase.Liquibase;
import liquibase.changelog.ChangeLogHistoryServiceFactory;
import liquibase.changelog.ChangeSet;
import liquibase.changelog.RanChangeSet;
import liquibase.database.Database;
import liquibase.database.DatabaseFactory;
import liquibase.database.jvm.JdbcConnection;
import liquibase.exception.LiquibaseException;
import liquibase.resource.DirectoryResourceAccessor;
import uk.gov.companieshouse.addresslookup.lamda.schema.MigrationResult.Status;

import java.io.StringWriter;
import java.nio.file.Path;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.List;
import java.util.Properties;
import java.util.function.LongSupplier;

/**
 * Wraps Liquibase. One instance per execution environment; one database connection per invocation.
 *
 * <p>UPDATE applies changesets one at a time and checks the remaining invocation time before each, so a long
 * migration returns {@link Status#PARTIAL} while it can still release the Liquibase lock cleanly, instead of being
 * killed by the Lambda timeout with the lock held. The pipeline simply invokes UPDATE again to resume; Liquibase's
 * DATABASECHANGELOG makes that idempotent.
 */
public final class SchemaMigrator {

    static final String APPLICATION_NAME = "address-lookup-schema-migrator";
    static final String TAG_PREFIX = "db-schema-";
    private static final int MAX_SQL_PREVIEW_CHARS = 256 * 1024;

    static {
        // No outbound calls from a function holding privileged credentials.
        System.setProperty("liquibase.analytics.enabled", "false");
        System.setProperty("liquibase.showBanner", "false");
    }

    private final MigratorConfiguration config;

    public SchemaMigrator(MigratorConfiguration config) {
        this.config = config;
    }

    public MigrationResult migrate(MigrationRequest request, Path changelogRoot, DatabaseCredentials credentials,
                                   LongSupplier remainingMillis) throws Exception {
        try (Connection connection = DriverManager.getConnection(config.jdbcUrl(), connectionProperties(credentials))) {
            Database database = DatabaseFactory.getInstance()
                    .findCorrectDatabaseImplementation(new JdbcConnection(connection));

            try (Liquibase liquibase = new Liquibase(MigratorConfiguration.MASTER_CHANGELOG,
                    new DirectoryResourceAccessor(changelogRoot), database)) {
                config.changeLogParameters().forEach(liquibase::setChangeLogParameter);
                Run run = new Run(request, liquibase, connection, new Contexts(config.contexts()),
                        new LabelExpression(config.labels()), config.minRemainingMillis());

                return switch (request.mode()) {
                    case VALIDATE -> run.validate();
                    case UPDATE -> run.update(remainingMillis);
                    case STATUS -> run.status();
                    case RELEASE_LOCKS -> run.releaseLocks();
                };
            }
        }
    }

    private Properties connectionProperties(DatabaseCredentials credentials) {
        Properties properties = new Properties();
        properties.setProperty("user", credentials.username());
        properties.setProperty("password", credentials.password());
        properties.setProperty("ApplicationName", APPLICATION_NAME);
        properties.setProperty("connectTimeout", "10");
        // A statement is cancelled by the server before the Lambda timeout, so Liquibase always gets the chance
        // to roll back and release its lock. lock_timeout stops a migration queueing behind live traffic.
        properties.setProperty("options",
                "-c statement_timeout=" + config.statementTimeoutMillis() + " -c lock_timeout=30000");
        return properties;
    }

    private record Run(MigrationRequest request, Liquibase liquibase, Connection connection, Contexts contexts,
                       LabelExpression labels, long minRemainingMillis) {

        MigrationResult validate() throws Exception {
            liquibase.validate();
            List<ChangeSet> pending = pending();
            StringWriter sql = new StringWriter();
            if (!pending.isEmpty()) {
                liquibase.update(pending.size(), contexts, labels, sql);
            }
            return result(Status.VALIDATED, null, ids(pending), List.of(), preview(sql.toString()));
        }

        MigrationResult update(LongSupplier remainingMillis) throws Exception {
            liquibase.validate();
            List<ChangeSet> pending = pending();
            List<String> applied = new ArrayList<>();

            for (ChangeSet changeSet : pending) {
                if (remainingMillis.getAsLong() < minRemainingMillis) {
                    return result(Status.PARTIAL, null, ids(pending.subList(applied.size(), pending.size())),
                            applied, null);
                }
                liquibase.update(1, contexts, labels);
                applied.add(changeSet.toString(false));
            }

            return result(Status.COMPLETE, tagIfUntagged(), List.of(), applied, null);
        }

        MigrationResult status() throws Exception {
            liquibase.validate();
            List<ChangeSet> pending = pending();
            return result(pending.isEmpty() ? Status.UP_TO_DATE : Status.PENDING, latestTag(), ids(pending),
                    List.of(), null);
        }

        MigrationResult releaseLocks() throws Exception {
            liquibase.forceReleaseLocks();
            return result(Status.LOCKS_RELEASED, null, List.of(), List.of(), null);
        }

        private List<ChangeSet> pending() throws LiquibaseException {
            return liquibase.listUnrunChangeSets(contexts, labels);
        }

        /**
         * Tags the most recent changeset with this release, so DATABASECHANGELOG records which db-schema version
         * brought the database to its current state. A release that applies nothing leaves the previous release's
         * tag in place rather than moving it.
         */
        private String tagIfUntagged() throws LiquibaseException {
            List<RanChangeSet> ran = ranChangeSets();
            if (ran.isEmpty()) {
                return null;
            }
            String latest = ran.get(ran.size() - 1).getTag();
            if (latest != null) {
                return latest;
            }
            String tag = TAG_PREFIX + request.version();
            liquibase.tag(tag);
            return tag;
        }

        private String latestTag() throws LiquibaseException {
            List<RanChangeSet> ran = ranChangeSets();
            return ran.isEmpty() ? null : ran.get(ran.size() - 1).getTag();
        }

        private List<RanChangeSet> ranChangeSets() throws LiquibaseException {
            return ChangeLogHistoryServiceFactory.getInstance()
                    .getChangeLogService(liquibase.getDatabase())
                    .getRanChangeSets(true);
        }

        private MigrationResult result(Status status, String tag, List<String> pending, List<String> applied,
                                       String sql) throws SQLException {
            return new MigrationResult(status, request.mode(), request.version(), tag, pending, applied,
                    postgisVersion(), sql);
        }

        private String postgisVersion() throws SQLException {
            try (PreparedStatement query = connection.prepareStatement(
                    "SELECT extversion FROM pg_catalog.pg_extension WHERE extname = 'postgis'");
                 ResultSet rows = query.executeQuery()) {
                return rows.next() ? rows.getString(1) : null;
            }
        }

        private static List<String> ids(List<ChangeSet> changeSets) {
            return changeSets.stream().map(changeSet -> changeSet.toString(false)).toList();
        }

        private static String preview(String sql) {
            if (sql.isEmpty()) {
                return null;
            }
            return sql.length() <= MAX_SQL_PREVIEW_CHARS ? sql
                    : sql.substring(0, MAX_SQL_PREVIEW_CHARS) + "\n-- truncated after " + MAX_SQL_PREVIEW_CHARS + " characters";
        }
    }
}
