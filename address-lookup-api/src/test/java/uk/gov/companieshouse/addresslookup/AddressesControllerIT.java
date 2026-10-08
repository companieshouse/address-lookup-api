package uk.gov.companieshouse.addresslookup;

// Compare response values with JUnit assertions.
import static org.junit.jupiter.api.Assertions.assertEquals;

import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;
import static org.hamcrest.Matchers.greaterThan;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static uk.gov.companieshouse.logging.util.LogContextProperties.REQUEST_ID;

import org.junit.jupiter.api.Test;
import java.util.stream.Stream;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;
import org.junit.jupiter.params.provider.ValueSource;

// Read JSON responses so the test can compare lookup results but allow the echoed input postcode to differ.
import com.fasterxml.jackson.databind.JsonNode;

// Parse each API response body into JSON for field-by-field comparison.
import com.fasterxml.jackson.databind.ObjectMapper;

// Retain each MockMvc HTTP response for comparison after both requests complete.
import org.springframework.test.web.servlet.MvcResult;

/**
 * Integration tests for the /addresses endpoint.
 * Tests the full address lookup response with total results and address list.
 */

class AddressesControllerIT extends AddressTestBaseIT {

    // ========================
    // Happy Path Tests
    // ========================

    @Test
    void shouldReturnAddressesGBPostcode() throws Exception {
        ObjectMapper objectMapper = new ObjectMapper();

        // Preserve the existing seeded-address checks while retaining this response for comparison.
        MvcResult unspacedResponse = this.mockMvc.perform(get("/address-lookup-api/addresses")
                        .queryParam("postcode", "WF27QD")
                        .header(REQUEST_ID.value(), "request_id"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.totalResults").value(1))
                .andExpect(jsonPath("$.addresses[0].udprn").value(26394069))
                .andExpect(jsonPath("$.addresses[0].postcode").value("WF2 7QD"))
                .andReturn();

        // Exercise the same API and seeded record with the spaced input form.
        MvcResult spacedResponse = this.mockMvc.perform(get("/address-lookup-api/addresses")
                        .queryParam("postcode", "WF2 7QD")
                        .header(REQUEST_ID.value(), "request_id"))
                .andExpect(status().isOk())
                .andReturn();

        // Parse both response bodies so we can compare their result fields separately from the echoed input.
        JsonNode unspacedJson = objectMapper.readTree(
                unspacedResponse.getResponse().getContentAsString());
        JsonNode spacedJson = objectMapper.readTree(
                spacedResponse.getResponse().getContentAsString());

        // The response echoes each request's original postcode, so verify each echo rather than expecting them to match.
        assertEquals("WF27QD", unspacedJson.get("postcode").asText());
        assertEquals("WF2 7QD", spacedJson.get("postcode").asText());

        // Matching counts and full address arrays prove both input forms resolve to the same lookup results.
        assertEquals(unspacedJson.get("totalResults"), spacedJson.get("totalResults"));
        assertEquals(unspacedJson.get("addresses"), spacedJson.get("addresses"));
    }

    @ParameterizedTest
    @MethodSource("addresses")
    void shouldReturnAddressesIslPostcode(
            String postcode, String expectedPostcode, String expectedPostTown) throws Exception {
        this.mockMvc.perform(get("/address-lookup-api/addresses")
                        .queryParam("postcode", postcode)
                        .header(REQUEST_ID.value(), "request_id"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.totalResults").value(greaterThan(1)))
                .andExpect(jsonPath("$.addresses[0].postcode").value(expectedPostcode))
                .andExpect(jsonPath("$.addresses[0].postTown").value(expectedPostTown));
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

    private static Stream<Arguments> addresses() {
        return Stream.of(
                Arguments.of("BT100EQ", "BT10 0EQ", "BELFAST"),
                Arguments.of("BT513TU", "BT51 3TU", "COLERAINE"),
                Arguments.of("GY79AD", "GY7 9AD", "GUERNSEY"),
                Arguments.of("IM11BD", "IM1 1BD", "ISLE OF MAN"),
                Arguments.of("JE27NA", "JE2 7NA", "JERSEY"));
    }
}
