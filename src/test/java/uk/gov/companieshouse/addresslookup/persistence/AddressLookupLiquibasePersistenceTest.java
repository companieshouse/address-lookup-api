package uk.gov.companieshouse.addresslookup.persistence;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import liquibase.exception.LiquibaseException;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.context.junit.jupiter.SpringJUnitConfig;

import java.sql.Connection;
import java.sql.DatabaseMetaData;
import java.sql.DriverManager;
import java.sql.ResultSet;
import java.sql.SQLException;

@SpringJUnitConfig
@TestPropertySource("classpath:application-test.properties")
public class AddressLookupLiquibasePersistenceTest {

    @Value("${test.db.url}")
    private String jdbcUrl;

    @Value("${test.db.user}")
    private String username;

    @Value("${test.db.password}")
    private String password;

    @Value("${test.db.changelog.path}")
    private String changelogPath;

    private Connection connection;

    @BeforeEach
    void setUp() throws LiquibaseException, SQLException {
        // Run Liquibase migrations to initialize the schema
        LiquibaseTestExecutor.runLiquibaseMigrations(jdbcUrl, username, password, changelogPath);

        connection = DriverManager.getConnection(jdbcUrl, username, password);
    }

    @AfterEach
    void tearDown() throws SQLException {
        if (connection != null && !connection.isClosed()) {
            connection.close();
        }
    }

    @Test
    public void testGetAddGBBuiltAddress() throws SQLException {

        ResultSet result = connection.createStatement().executeQuery("SELECT * FROM add_gb_builtaddress WHERE uprn = 1");

        assertTrue(result.first());

        assertEquals("TEST_town_name", result.getString("town_name"));
        assertEquals("AA1A A11", result.getString("postcode"));

    }

    @Test
    public void testGetAddIslBuiltAddress() throws SQLException {

        ResultSet result = connection.createStatement().executeQuery("SELECT * FROM add_isl_builtaddress WHERE uprn = 1");

        assertTrue(result.first());

        assertEquals("TEST_town_name", result.getString("town_name"));
        assertEquals("AA1A A11", result.getString("postcode"));

    }

    @Test
    public void testGetAddGBRoyalMailAddress() throws SQLException {

        ResultSet result = connection.createStatement().executeQuery("SELECT * FROM add_gb_royalmailaddress WHERE uprn = 1");

        assertTrue(result.first());

        assertEquals("TEST_post_town", result.getString("post_town"));
        assertEquals("AA1A 1AA", result.getString("postcode"));

    }

    @Test
    public void testGetAddIslRoyalMailAddress() throws SQLException {

        ResultSet result = connection.createStatement().executeQuery("SELECT * FROM add_isl_royalmailaddress WHERE uprn = 1");

        assertTrue(result.first());

        assertEquals("TEST_post_town", result.getString("post_town"));
        assertEquals("AA1A 1AA", result.getString("postcode"));

    }

    @Test
    void testDatabaseSchemaInitialized() throws SQLException {
        // Verify the "DATABASECHANGELOG" table exists (using JDBC)
        DatabaseMetaData metaData = connection.getMetaData();
        ResultSet tables = metaData.getTables(null, null, "DATABASECHANGELOG", new String[]{"TABLE"});

        // Assert the table exists
        assertTrue(tables.next(), "Tables  not created by Liquibase");
    }

}