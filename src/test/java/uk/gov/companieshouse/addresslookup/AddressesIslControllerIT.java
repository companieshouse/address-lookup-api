package uk.gov.companieshouse.addresslookup;

import static org.hamcrest.Matchers.greaterThan;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static uk.gov.companieshouse.logging.util.LogContextProperties.REQUEST_ID;

import java.util.stream.Stream;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;

/**
 * Integration tests for the /addresses endpoint with ISL (Northern Ireland) postcodes.
 * Tests address lookup responses for Northern Ireland postcodes.
 */

class AddressesIslControllerIT extends AddressTestBaseIT {

    // ========================
    // Happy Path Tests
    // ========================

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

    private static Stream<Arguments> addresses() {
        return Stream.of(
                Arguments.of("BT100EQ", "BT10 0EQ", "BELFAST"),
                Arguments.of("BT513TU", "BT51 3TU", "COLERAINE"),
                Arguments.of("GY79AD", "GY7 9AD", "GUERNSEY"),
                Arguments.of("IM11BD", "IM1 1BD", "ISLE OF MAN"),
                Arguments.of("JE27NA", "JE2 7NA", "JERSEY"));
    }
}
