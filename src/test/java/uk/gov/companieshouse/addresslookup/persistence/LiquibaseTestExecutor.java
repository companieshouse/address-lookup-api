package uk.gov.companieshouse.addresslookup.persistence;

import liquibase.Liquibase;
import liquibase.database.Database;
import liquibase.database.DatabaseFactory;
import liquibase.database.jvm.JdbcConnection;
import liquibase.exception.LiquibaseException;
import liquibase.resource.ClassLoaderResourceAccessor;

import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.SQLException;

public class LiquibaseTestExecutor {

    /**
     * Runs Liquibase migrations to update the database schema.
     * @param jdbcUrl JDBC URL of the target database
     * @param username Database username
     * @param password Database password
     * @param changelogPath Path to the Liquibase changelog file (classpath relative)
     * @throws LiquibaseException If migration fails
     * @throws SQLException If database connection fails
     */
    public synchronized static void runLiquibaseMigrations(String jdbcUrl, String username, String password, String changelogPath)
            throws LiquibaseException, SQLException {

        // Step 1: Connect to the database
        try (Connection connection = DriverManager.getConnection(jdbcUrl, username, password)) {

            // Step 2: Create a Liquibase Database object from the connection
            Database database = DatabaseFactory.getInstance()
                    .findCorrectDatabaseImplementation(new JdbcConnection(connection));

            // Step 3: Initialize Liquibase with the changelog and resource accessor
            Liquibase liquibase = new Liquibase(
                    changelogPath,
                    new ClassLoaderResourceAccessor(), // Reads changelog from classpath
                    database
            );

            // Step 4: Run all pending migrations
            liquibase.update();

            // Include test data
            liquibase = new Liquibase(
                    "db/changelog/test/db.changelog-test.yaml",
                    new ClassLoaderResourceAccessor(), // Reads changelog from classpath
                    database
            );
            liquibase.update();
        }
    }
}
