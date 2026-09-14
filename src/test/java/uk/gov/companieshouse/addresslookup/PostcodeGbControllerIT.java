package uk.gov.companieshouse.addresslookup;

import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static uk.gov.companieshouse.logging.util.LogContextProperties.REQUEST_ID;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

/**
 * Integration tests for the /postcode endpoint.
 * Tests the single address lookup without premise, including validation edge cases.
 */

class PostcodeGbControllerIT extends AddressTestBaseIT {

    // ========================
    // Happy Path Tests
    // ========================

    @Test
    void shouldReturnAddressGBPostcode() throws Exception {
        this.mockMvc.perform(get("/address-lookup-api/postcode")
                        .queryParam("postcode", "WF27QD")
                        .header(REQUEST_ID.value(), "request_id"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.postcode").value("WF2 7QD"))
                .andExpect(jsonPath("$.premise").doesNotExist())
                .andExpect(jsonPath("$.addressLine1").value("WOODMOOR ROAD"))
                .andExpect(jsonPath("$.postTown").value("WAKEFIELD"))
                .andExpect(jsonPath("$.country").value("GB-ENG"));
    }

    // ========================
    // Validation Tests
    // ========================

    @ParameterizedTest
    @ValueSource(strings = {
        "", 
        "   ", 
        "A",
        "INVALID",  
        "INVALID@POSTCODE", 
        "ZZ1 1ZZ",
        "BT557KLEXTRA",
        "AB12 4NY"
    })
    void shouldReturnNotFoundForInvalidPostcodes(String postcode) throws Exception {
        this.mockMvc.perform(get("/address-lookup-api/postcode")
                        .queryParam("postcode", postcode)
                        .header(REQUEST_ID.value(), "request_id"))
                .andExpect(status().isNotFound());
    }

    // ========================
    // Response Structure Validation Tests
    // ========================

    @Test
    void shouldVerifyPremiseFieldDoesNotExist() throws Exception {
        this.mockMvc.perform(get("/address-lookup-api/postcode")
                        .queryParam("postcode", "WF27QD")
                        .header(REQUEST_ID.value(), "request_id"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.premise").doesNotExist());
    }
}
