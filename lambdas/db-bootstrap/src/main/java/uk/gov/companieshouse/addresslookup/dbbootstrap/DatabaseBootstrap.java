package uk.gov.companieshouse.addresslookup.dbbootstrap;

import org.postgresql.PGConnection;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.Arrays;

/**
 * Prepares the database before the first Flyway run, connected as the master user. Idempotent: re-running it
 * (for example after a password rotation in Vault) only resets the schema owner's password and re-applies grants.
 * It never creates schemas or tables, so the database stays empty for Flyway's V2026.001 baseline.
 */
public final class DatabaseBootstrap {

    private DatabaseBootstrap() {
    }

    public static void bootstrap(Connection master, String masterUsername, DatabaseCredentials owner) throws SQLException {
        DatabaseCredentials.require(!owner.username().equals(masterUsername),
                "The schema owner must not be the master user");
        boolean autoCommit = master.getAutoCommit();
        master.setAutoCommit(true);
        try {
            // Installed as the master (rds_superuser) so the baseline's CREATE EXTENSION IF NOT EXISTS is a no-op.
            execute(master, "CREATE EXTENSION IF NOT EXISTS postgis");

            if (roleExists(master, owner.username())) {
                execute(master, quoted(master, "ALTER ROLE %I LOGIN", owner.username()));
            } else {
                execute(master, quoted(master, "CREATE ROLE %I LOGIN", owner.username()));
            }
            // Hashed client side (SCRAM), so the plaintext never reaches the server or its logs.
            char[] password = owner.password().toCharArray();
            try {
                master.unwrap(PGConnection.class).alterUserPassword(owner.username(), password, "scram-sha-256");
            } finally {
                Arrays.fill(password, '\0');
            }

            execute(master, quoted(master, "GRANT CONNECT, CREATE ON DATABASE %I TO %I", null, owner.username()));
            // PostgreSQL 15+ no longer lets everyone create in public; Flyway keeps flyway_schema_history there
            // unless the migration Lambda sets a default schema.
            execute(master, quoted(master, "GRANT USAGE, CREATE ON SCHEMA public TO %I", owner.username()));
        } finally {
            master.setAutoCommit(autoCommit);
        }
    }

    private static boolean roleExists(Connection connection, String role) throws SQLException {
        try (PreparedStatement statement = connection.prepareStatement("SELECT 1 FROM pg_roles WHERE rolname = ?")) {
            statement.setString(1, role);
            try (ResultSet rs = statement.executeQuery()) {
                return rs.next();
            }
        }
    }

    /**
     * Builds DDL with the server's format(), so identifiers are quoted by PostgreSQL itself (%I) and never
     * concatenated from input. A null database argument means current_database().
     */
    private static String quoted(Connection connection, String template, String role) throws SQLException {
        try (PreparedStatement statement = connection.prepareStatement("SELECT format(?, ?::text)")) {
            statement.setString(1, template);
            statement.setString(2, role);
            return single(statement);
        }
    }

    private static String quoted(Connection connection, String template, String database, String role) throws SQLException {
        try (PreparedStatement statement = connection.prepareStatement(
                "SELECT format(?, coalesce(?::text, current_database()), ?::text)")) {
            statement.setString(1, template);
            statement.setString(2, database);
            statement.setString(3, role);
            return single(statement);
        }
    }

    private static String single(PreparedStatement statement) throws SQLException {
        try (ResultSet rs = statement.executeQuery()) {
            rs.next();
            return rs.getString(1);
        }
    }

    private static void execute(Connection connection, String sql) throws SQLException {
        try (Statement statement = connection.createStatement()) {
            statement.execute(sql);
        }
    }
}
