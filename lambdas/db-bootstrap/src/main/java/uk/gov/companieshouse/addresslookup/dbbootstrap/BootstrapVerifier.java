package uk.gov.companieshouse.addresslookup.dbbootstrap;

import java.io.IOException;
import java.security.GeneralSecurityException;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.List;

/**
 * Checks the ALS-90 acceptance criteria against the live database. Any failure is reported together and makes
 * {@link #verify} throw, so the Lambda returns a FunctionError and the Terraform apply fails.
 */
public final class BootstrapVerifier {

    private static final String TLS_1_3 = "TLSv1.3";

    public enum DatabaseState {
        /** No application objects: the first Flyway run will build everything from V2026.001. */
        EMPTY,
        /** flyway_schema_history exists, so Flyway owns the schema. */
        MIGRATED,
        /** Application objects but no flyway_schema_history: Flyway would stop with a non-empty schema error. */
        NOT_EMPTY
    }

    public record Report(String postgisVersion, String schemaOwner, DatabaseState databaseState, List<String> objects,
                         String forceSsl, String sslMinProtocolVersion, String connectionTls,
                         String tls12, String tls13, List<String> failures) {
    }

    private static final String HISTORY_SQL = """
            SELECT n.nspname FROM pg_class c JOIN pg_namespace n ON n.oid = c.relnamespace
            WHERE n.nspname = 'public' AND c.relname = 'flyway_schema_history' AND c.relkind IN ('r', 'p')
            """;

    /** Relations and schemas that are neither system nor owned by an extension (PostGIS's spatial_ref_sys is). */
    private static final String OBJECTS_SQL = """
            SELECT name FROM (
                SELECT n.nspname || '.' || c.relname AS name
                FROM pg_class c JOIN pg_namespace n ON n.oid = c.relnamespace
                WHERE c.relkind IN ('r', 'p', 'v', 'm', 'S', 'f')
                  AND n.nspname NOT IN ('pg_catalog', 'information_schema') AND n.nspname NOT LIKE 'pg\\_%'
                  AND NOT EXISTS (SELECT 1 FROM pg_depend d WHERE d.classid = 'pg_class'::regclass
                                  AND d.objid = c.oid AND d.deptype = 'e')
                UNION ALL
                SELECT 'schema ' || n.nspname
                FROM pg_namespace n
                WHERE n.nspname NOT IN ('pg_catalog', 'information_schema', 'public') AND n.nspname NOT LIKE 'pg\\_%'
                  AND NOT EXISTS (SELECT 1 FROM pg_depend d WHERE d.classid = 'pg_namespace'::regclass
                                  AND d.objid = n.oid AND d.deptype = 'e')
            ) objects ORDER BY name LIMIT 20
            """;

    private static final String OWNER_SQL = """
            SELECT r.rolsuper, r.rolcanlogin,
                   CASE WHEN EXISTS (SELECT 1 FROM pg_roles WHERE rolname = 'rds_superuser')
                        THEN pg_has_role(r.oid, 'rds_superuser', 'MEMBER') ELSE false END,
                   has_database_privilege(r.oid, current_database(), 'CONNECT'),
                   has_database_privilege(r.oid, current_database(), 'CREATE'),
                   has_schema_privilege(r.oid, 'public', 'USAGE') AND has_schema_privilege(r.oid, 'public', 'CREATE')
            FROM pg_roles r WHERE r.rolname = ?
            """;

    private final TlsProtocolProbe probe;

    /** @param probe null skips the TLS checks (local PostgreSQL without TLS) */
    public BootstrapVerifier(TlsProtocolProbe probe) {
        this.probe = probe;
    }

    public Report verify(Connection master, DatabaseCredentials masterCredentials, String schemaOwner)
            throws SQLException, IOException, GeneralSecurityException {
        List<String> failures = new ArrayList<>();

        String postgis = string(master, "SELECT extversion FROM pg_extension WHERE extname = 'postgis'", null);
        if (postgis == null) {
            failures.add("postgis is not in pg_extension");
        }

        checkOwner(master, masterCredentials.username(), schemaOwner, failures);

        List<String> objects = List.of();
        DatabaseState state;
        if (string(master, HISTORY_SQL, null) != null) {
            state = DatabaseState.MIGRATED;
        } else {
            objects = strings(master, OBJECTS_SQL);
            state = objects.isEmpty() ? DatabaseState.EMPTY : DatabaseState.NOT_EMPTY;
            if (!objects.isEmpty()) {
                failures.add("database has application objects but no flyway_schema_history, so Flyway would "
                        + "refuse to migrate it: " + objects);
            }
        }

        String forceSsl = null;
        String minProtocol = null;
        String connectionTls = null;
        String tls12 = null;
        String tls13 = null;
        if (probe != null) {
            forceSsl = string(master, "SELECT current_setting('rds.force_ssl', true)", null);
            minProtocol = string(master, "SELECT current_setting('ssl_min_protocol_version', true)", null);
            connectionTls = string(master, "SELECT version FROM pg_stat_ssl WHERE pid = pg_backend_pid()", null);
            checkTlsSettings(forceSsl, minProtocol, connectionTls, failures);
            TlsProtocolProbe.Outcome old = probe.probe(masterCredentials.host(), masterCredentials.port(), "TLSv1.2");
            TlsProtocolProbe.Outcome current = probe.probe(masterCredentials.host(), masterCredentials.port(), TLS_1_3);
            tls12 = old.result().name();
            tls13 = current.result().name();
            if (old.result() != TlsProtocolProbe.Result.REFUSED) {
                failures.add("a TLS 1.2 client was not refused: " + old);
            }
            if (current.result() != TlsProtocolProbe.Result.ACCEPTED) {
                failures.add("a TLS 1.3 client was not accepted: " + current);
            }
        }

        Report report = new Report(postgis, schemaOwner, state, objects, forceSsl, minProtocol, connectionTls,
                tls12, tls13, List.copyOf(failures));
        if (!failures.isEmpty()) {
            throw new VerificationException(report);
        }
        return report;
    }

    private static void checkTlsSettings(String forceSsl, String minProtocol, String connectionTls,
            List<String> failures) {
        if (!"1".equals(forceSsl) && !"on".equals(forceSsl)) {
            failures.add("rds.force_ssl is " + forceSsl + ", expected 1 (on)");
        }
        if (!TLS_1_3.equals(minProtocol)) {
            failures.add("ssl_min_protocol_version is " + minProtocol + ", expected " + TLS_1_3);
        }
        if (!TLS_1_3.equals(connectionTls)) {
            failures.add("this connection uses " + connectionTls + ", expected " + TLS_1_3);
        }
    }

    private static void checkOwner(Connection master, String masterUsername, String owner, List<String> failures)
            throws SQLException {
        if (owner.equals(masterUsername)) {
            failures.add("the schema owner is the master user");
        }
        try (PreparedStatement statement = master.prepareStatement(OWNER_SQL)) {
            statement.setString(1, owner);
            try (ResultSet rs = statement.executeQuery()) {
                if (!rs.next()) {
                    failures.add("role " + owner + " does not exist");
                    return;
                }
                if (rs.getBoolean(1)) {
                    failures.add(owner + " is a superuser");
                }
                if (!rs.getBoolean(2)) {
                    failures.add(owner + " cannot log in");
                }
                if (rs.getBoolean(3)) {
                    failures.add(owner + " is a member of rds_superuser");
                }
                if (!rs.getBoolean(4) || !rs.getBoolean(5)) {
                    failures.add(owner + " lacks CONNECT or CREATE on the database");
                }
                if (!rs.getBoolean(6)) {
                    failures.add(owner + " lacks USAGE or CREATE on schema public");
                }
            }
        }
    }

    private static String string(Connection connection, String sql, String parameter) throws SQLException {
        try (PreparedStatement statement = connection.prepareStatement(sql)) {
            if (parameter != null) {
                statement.setString(1, parameter);
            }
            try (ResultSet rs = statement.executeQuery()) {
                return rs.next() ? rs.getString(1) : null;
            }
        }
    }

    private static List<String> strings(Connection connection, String sql) throws SQLException {
        List<String> values = new ArrayList<>();
        try (PreparedStatement statement = connection.prepareStatement(sql); ResultSet rs = statement.executeQuery()) {
            while (rs.next()) {
                values.add(rs.getString(1));
            }
        }
        return values;
    }

    /** Thrown when any check fails; carries the full report. */
    public static final class VerificationException extends IllegalStateException {

        private final transient Report report;

        VerificationException(Report report) {
            super("Database is not ready for Flyway: " + String.join("; ", report.failures()));
            this.report = report;
        }

        public Report report() {
            return report;
        }
    }
}
