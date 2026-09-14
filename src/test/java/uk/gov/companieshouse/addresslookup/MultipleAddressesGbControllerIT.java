package uk.gov.companieshouse.addresslookup;

import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;
import static org.hamcrest.Matchers.greaterThan;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static uk.gov.companieshouse.logging.util.LogContextProperties.REQUEST_ID;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

/**
 * Integration tests for the /multiple-addresses endpoint.
 * Tests the legacy address format with premise information and country mapping.
 */

class MultipleAddressesGbControllerIT extends AddressTestBaseIT {

    // ========================
    // Happy Path Tests
    // ========================

    @Test
    void shouldReturnMultipleAddressesGBPostcode() throws Exception {
        this.mockMvc.perform(get("/address-lookup-api/multiple-addresses")
                        .queryParam("postcode", "AB101AU")
                        .header(REQUEST_ID.value(), "request_id"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$").isArray())
                .andExpect(jsonPath("$.length()").value(greaterThan(1)))
                .andExpect(jsonPath("$[0].postcode").value("AB10 1AU"))
                .andExpect(jsonPath("$[0].premise").value("14"))
                .andExpect(jsonPath("$[0].addressLine1").value("NETHERKIRKGATE"))
                .andExpect(jsonPath("$[0].postTown").value("ABERDEEN"))
                .andExpect(jsonPath("$[0].country").value("GB-SCT"))
                .andExpect(jsonPath("$[1].postcode").value("AB10 1AU"))
                .andExpect(jsonPath("$[1].premise").value("FLAT 1, 16"))
                .andExpect(jsonPath("$[1].addressLine1").value("NETHERKIRKGATE"))
                .andExpect(jsonPath("$[1].country").value("GB-SCT"));
    }

    // ========================
    // Validation Tests
    // ========================

    @ParameterizedTest
    @ValueSource(strings = {
        "   ",
        "A",
        "AB124NYEXTRA",
        "ZZ1 1ZZ",
        "INVALID",
        "INVALID@POSTCODE",
        "AB12 4NY"
    })
    void shouldReturnEmptyArrayForUnknownPostcode(String postcode) throws Exception {
        this.mockMvc.perform(get("/address-lookup-api/multiple-addresses")
                        .queryParam("postcode", postcode)
                        .header(REQUEST_ID.value(), "request_id"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$").isArray())
                .andExpect(jsonPath("$").isEmpty());
    }

    @Test
    void shouldReturnNotFoundForEmptyPostcode() throws Exception {
        this.mockMvc.perform(get("/address-lookup-api/multiple-addresses")
                        .queryParam("postcode", "")
                        .header(REQUEST_ID.value(), "request_id"))
                .andExpect(status().isNotFound());
    }

    @Test
    void shouldVerifyAllAddressesHavePremiseField() throws Exception {
        this.mockMvc.perform(get("/address-lookup-api/multiple-addresses")
                        .queryParam("postcode", "AB101AU")
                        .header(REQUEST_ID.value(), "request_id"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$").isArray())
                .andExpect(jsonPath("$.length()").value(greaterThan(1)))
                .andExpect(jsonPath("$[0].premise").exists())
                .andExpect(jsonPath("$[1].premise").exists())
                .andExpect(jsonPath("$[2].premise").exists());
    }

}
