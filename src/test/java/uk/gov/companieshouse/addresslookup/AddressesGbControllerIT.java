package uk.gov.companieshouse.addresslookup;

import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static uk.gov.companieshouse.logging.util.LogContextProperties.REQUEST_ID;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

/**
 * Integration tests for the /addresses endpoint.
 * Tests the full address lookup response with total results and address list.
 */

class AddressesGbControllerIT extends AddressTestBaseIT {

    // ========================
    // Happy Path Tests
    // ========================

    @Test
    void shouldReturnAddressesGBPostcode() throws Exception {
        this.mockMvc.perform(get("/address-lookup-api/addresses")
                        .queryParam("postcode", "WF27QD")
                        .header(REQUEST_ID.value(), "request_id"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.totalResults").value(1))
                .andExpect(jsonPath("$.addresses[0].udprn").value(26394069))
                .andExpect(jsonPath("$.addresses[0].postcode").value("WF2 7QD"));
    }

    // ========================
    // Validation Tests
    // ========================

    @ParameterizedTest
    @ValueSource(strings = {
        "",
        "   ",
        "ZZ1 1ZZ",
        "INVALID",
        "A VERY LONG POSTCODE",
        "INVALID@POSTCODE"
    })
    void shouldReturnEmptyResultsForUnknownPostcode(String postcode) throws Exception {
        this.mockMvc.perform(get("/address-lookup-api/addresses")
                        .queryParam("postcode", postcode)
                        .header(REQUEST_ID.value(), "request_id"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.totalResults").value(0))
                .andExpect(jsonPath("$.addresses").isArray())
                .andExpect(jsonPath("$.addresses").isEmpty());
    }
}
