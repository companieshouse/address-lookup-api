package uk.gov.companieshouse.addresslookup;

import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static uk.gov.companieshouse.logging.util.LogContextProperties.REQUEST_ID;

import java.util.stream.Stream;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;

/**
 * Integration tests for the /postcode endpoint with ISL (Northern Ireland) postcodes.
 * Tests the single address lookup without premise for Northern Ireland, including validation edge cases.
 */
        
class PostcodeIslControllerIT extends AddressTestBaseIT {

    // ========================
    // Happy Path Tests
    // ========================

        @ParameterizedTest
        @MethodSource("addresses")
        void shouldReturnAddressIslPostcode(
            String postcode,
            String expectedPostcode,
            String expectedAddressLine1,
            String expectedPostTown,
            String expectedCountry) throws Exception {
        this.mockMvc.perform(get("/address-lookup-api/postcode")
            .queryParam("postcode", postcode)
            .header(REQUEST_ID.value(), "request_id"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.postcode").value(expectedPostcode))
                .andExpect(jsonPath("$.premise").doesNotExist())
                .andExpect(jsonPath("$.addressLine1").value(expectedAddressLine1))
                .andExpect(jsonPath("$.postTown").value(expectedPostTown))
                .andExpect(jsonPath("$.country").value(expectedCountry));
        }

        private static Stream<Arguments> addresses() {
        return Stream.of(
            Arguments.of("BT100BU", "BT10 0BU", "PORTER PARK", "BELFAST", "GB-NIR"),
            Arguments.of("BT513TU", "BT51 3TU", "GRANARY CLOSE", "COLERAINE", "United Kingdom"),
            Arguments.of("GY80EE", "GY8 0EE", "LES NOUETTES", "GUERNSEY", "United Kingdom"),
            Arguments.of("IM11BD", "IM1 1BD", "PRINCES STREET", "ISLE OF MAN", "United Kingdom"),
            Arguments.of("JE27ND", "JE2 7ND", "LANGLEY PARK", "JERSEY", "United Kingdom"));
    }

}
