package uk.gov.companieshouse.addresslookup;

import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultHandlers.print;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;
import static uk.gov.companieshouse.logging.util.LogContextProperties.REQUEST_ID;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.web.context.WebApplicationContext;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.utility.DockerImageName;

@AutoConfigureMockMvc
@SpringBootTest(
        classes = Application.class,
        webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
        properties = {
                "spring.docker.compose.enabled=false",
                "spring.liquibase.change-log=classpath:db/changelog/db.changelog-local.yaml"
        })
@Testcontainers
class AddressLookupApplicationIT {

    @SuppressWarnings("resource")
    @Container
    private static final PostgreSQLContainer<?> POSTGRES = new PostgreSQLContainer<>(
            DockerImageName.parse("postgis/postgis:18-3.6").asCompatibleSubstituteFor("postgres"))
            .withCreateContainerCmdModifier(command -> command.withPlatform("linux/amd64"));

    @DynamicPropertySource
    static void registerDataSourceProperties(DynamicPropertyRegistry registry) {
        registry.add("spring.datasource.url", POSTGRES::getJdbcUrl);
        registry.add("spring.datasource.username", POSTGRES::getUsername);
        registry.add("spring.datasource.password", POSTGRES::getPassword);
        registry.add("spring.datasource.driver-class-name", POSTGRES::getDriverClassName);
    }

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private WebApplicationContext context;

    @Test
    void shouldLoadApplicationContext() {
        assertNotNull(context);
    }

    @Test
    void shouldReturn200FromGetHealthEndpoint() throws Exception {
        this.mockMvc.perform(get("/address-lookup-api/healthcheck")
                        .header(REQUEST_ID.value(), "request_id"))
                .andDo(print())
                .andExpect(status().isOk())
                .andExpect(content().string("{\"status\":\"UP\"}"));
    }

    @Test
    void shouldReturnAddressesForPostcode() throws Exception {
        this.mockMvc.perform(get("/address-lookup-api/addresses")
                        .queryParam("postcode", "WF2 7QD")
                        .header(REQUEST_ID.value(), "request_id"))
                .andDo(print())
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.totalResults").value(1))
                .andExpect(jsonPath("$.addresses[0].udprn").value(26394069))
                .andExpect(jsonPath("$.addresses[0].postcode").value("WF2 7QD"));
    }

    @Test
    void shouldReturnIslAddressesForPostcode() throws Exception {
        this.mockMvc.perform(get("/address-lookup-api/addresses")
                        .queryParam("postcode", "BT1 1AR")
                        .header(REQUEST_ID.value(), "request_id"))
                .andDo(print())
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.totalResults").value(1))
                .andExpect(jsonPath("$.addresses[0].udprn").value(3073963))
                .andExpect(jsonPath("$.addresses[0].postcode").value("BT1 1AR"));
    }

    @Test
    void shouldReturnLegacyAddressesForPostcode() throws Exception {
        this.mockMvc.perform(get("/address-lookup-api/multiple-addresses")
                        .queryParam("postcode", "WF2 7QD")
                        .header(REQUEST_ID.value(), "request_id"))
                .andDo(print())
                .andExpect(status().isOk())
                .andExpect(jsonPath("$[0].postcode").value("WF2 7QD"))
                .andExpect(jsonPath("$[0].premise").value("57"))
                .andExpect(jsonPath("$[0].addressLine1").value("WOODMOOR ROAD"))
                .andExpect(jsonPath("$[0].postTown").value("WAKEFIELD"))
                .andExpect(jsonPath("$[0].country").value("GB-ENG"));
    }

    @Test
    void shouldReturnLegacyCountryFromGbBuiltAddress() throws Exception {
        this.mockMvc.perform(get("/address-lookup-api/multiple-addresses")
                        .queryParam("postcode", "AB124NY")
                        .header(REQUEST_ID.value(), "request_id"))
                .andDo(print())
                .andExpect(status().isOk())
                .andExpect(jsonPath("$[0].postcode").value("AB12 4NY"))
                .andExpect(jsonPath("$[0].premise").value("2"))
                .andExpect(jsonPath("$[0].country").value("GB-SCT"));
    }

    @Test
    void shouldReturnLegacyCountryFromIslBuiltAddress() throws Exception {
        this.mockMvc.perform(get("/address-lookup-api/multiple-addresses")
                        .queryParam("postcode", "BT11AR")
                        .header(REQUEST_ID.value(), "request_id"))
                .andDo(print())
                .andExpect(status().isOk())
                .andExpect(jsonPath("$[0].postcode").value("BT1 1AR"))
                .andExpect(jsonPath("$[0].premise").value("20"))
                .andExpect(jsonPath("$[0].addressLine1").value("DONEGALL QUAY"))
                .andExpect(jsonPath("$[0].postTown").value("BELFAST"))
                .andExpect(jsonPath("$[0].country").value("GB-NIR"));
    }

    @Test
    void shouldReturnLegacyAddressWithoutPremiseForPostcode() throws Exception {
        this.mockMvc.perform(get("/address-lookup-api/postcode")
                        .queryParam("postcode", "WF2 7QD")
                        .header(REQUEST_ID.value(), "request_id"))
                .andDo(print())
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.postcode").value("WF2 7QD"))
                .andExpect(jsonPath("$.premise").doesNotExist())
                .andExpect(jsonPath("$.addressLine1").value("WOODMOOR ROAD"))
                .andExpect(jsonPath("$.postTown").value("WAKEFIELD"))
                .andExpect(jsonPath("$.country").value("GB-ENG"));
    }

    @Test
    void shouldReturnIslLegacyAddressWithoutPremiseForPostcode() throws Exception {
        this.mockMvc.perform(get("/address-lookup-api/postcode")
                        .queryParam("postcode", "BT11AR")
                        .header(REQUEST_ID.value(), "request_id"))
                .andDo(print())
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.postcode").value("BT1 1AR"))
                .andExpect(jsonPath("$.premise").doesNotExist())
                .andExpect(jsonPath("$.addressLine1").value("DONEGALL QUAY"))
                .andExpect(jsonPath("$.postTown").value("BELFAST"))
                .andExpect(jsonPath("$.country").value("GB-NIR"));
    }

    @Test
    void shouldReturnNotFoundWhenPostcodeHasNoAddress() throws Exception {
        this.mockMvc.perform(get("/address-lookup-api/postcode")
                        .queryParam("postcode", "ZZ1 1ZZ")
                        .header(REQUEST_ID.value(), "request_id"))
                .andDo(print())
                .andExpect(status().isNotFound());
    }
}
