package uk.gov.companieshouse.addresslookup;

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

/**
 * Integration tests for the /multiple-addresses endpoint.
 * Tests the legacy address format with premise information and country mapping.
 */

class MultipleAddressesControllerIT extends AddressTestBaseIT {

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

    @ParameterizedTest
    @MethodSource("addresses")
    void shouldReturnMultipleAddressesIslPostcode(
            String postcode,
            String expectedPostcode,
            String expectedAddressLine1,
            String expectedPostTown,
            String expectedCountry) throws Exception {
        this.mockMvc.perform(get("/address-lookup-api/multiple-addresses")
            .queryParam("postcode", postcode)
            .header(REQUEST_ID.value(), "request_id"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$").isArray())
                .andExpect(jsonPath("$.length()").value(greaterThan(1)))
                .andExpect(jsonPath("$[0].postcode").value(expectedPostcode))
                .andExpect(jsonPath("$[0].addressLine1").value(expectedAddressLine1))
                .andExpect(jsonPath("$[0].postTown").value(expectedPostTown))
                .andExpect(jsonPath("$[0].country").value(expectedCountry));
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

    private static Stream<Arguments> addresses() {
        return Stream.of(
            Arguments.of("BT100EQ", "BT10 0EQ", "GARRON CRESCENT", "BELFAST", "GB-NIR"),
            Arguments.of("BT513TU", "BT51 3TU", "GRANARY CLOSE", "COLERAINE", "United Kingdom"),
            Arguments.of("GY79AD", "GY7 9AD", "RUE DES HECHES", "GUERNSEY", "United Kingdom"),
            Arguments.of("IM11BD", "IM1 1BD", "PRINCES STREET", "ISLE OF MAN", "United Kingdom"),
            Arguments.of("JE27NA", "JE2 7NA", "LES GRANDS VAUX", "JERSEY", "United Kingdom"));
    }

}
