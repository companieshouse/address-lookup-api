package uk.gov.companieshouse.addresslookup;

// Provides assertions for the expected table and view row counts.
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;
import static uk.gov.companieshouse.logging.util.LogContextProperties.REQUEST_ID;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.beans.factory.annotation.Autowired;
// Provides SQL queries against the Testcontainers database.
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.web.context.WebApplicationContext;

class AddressLookupApplicationIT extends AddressTestBaseIT {

    @Autowired
    private WebApplicationContext context;

    // Query the Testcontainers database initialized by AddressTestBaseIT to verify
    // the local Liquibase migrations and seed data directly.
    @Autowired
    private JdbcTemplate jdbcTemplate;

    @Test
    void shouldLoadApplicationContext() {
        assertNotNull(context);
    }

    @Test
    void shouldReturn200FromGetHealthEndpoint() throws Exception {
        this.mockMvc.perform(get("/address-lookup-api/healthcheck")
                        .header(REQUEST_ID.value(), "request_id"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("UP"));
    }

    // Each table is an independent case, so one missing table does not skip the others.
    @ParameterizedTest(name = "shouldLoadSeedTableInOsData: {0}")
    @ValueSource(strings = {
        "add_gb_builtaddress_v3",
        "add_isl_builtaddress_v3",
        "add_gb_royalmailaddress_v1",
        "add_isl_royalmailaddress_v1"
    })
    void shouldLoadSeedTableInOsData(String table) {
        Integer count = jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM information_schema.tables "
                        + "WHERE table_schema = ? AND table_name = ?",
                Integer.class,
                "os_data",
                table);
        assertEquals(1, count, "Expected " + table + " in os_data");
    }

    @Test
    void shouldPopulateLookupViewWithSeedRows() {
        Integer viewRows = jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM address_lookup.address_lookup",
                Integer.class);
        assertEquals(225, viewRows);
    }
}
