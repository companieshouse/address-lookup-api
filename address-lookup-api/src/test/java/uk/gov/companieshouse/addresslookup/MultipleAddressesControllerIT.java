package uk.gov.companieshouse.addresslookup;

import static org.hamcrest.Matchers.greaterThan;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;
import static uk.gov.companieshouse.logging.util.LogContextProperties.REQUEST_ID;

import java.util.stream.Stream;
import org.junit.jupiter.api.Test;
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
    this.mockMvc
        .perform(
            get("/address-lookup-api/multiple-addresses")
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
        .andExpect(jsonPath("$[1].premise").value("FLAT 1"))
        .andExpect(jsonPath("$[1].addressLine1").value("16 NETHERKIRKGATE"))
        .andExpect(jsonPath("$[1].country").value("GB-SCT"));
  }

  /** Verifies each approved regional fixture through the legacy list endpoint. */
  @ParameterizedTest
  @MethodSource("uk.gov.companieshouse.addresslookup.BorderPostcodeFixtures#cases")
  void shouldReturnMultipleAddressesForBorderPostcodes(
      String postcode, String expectedPostcode, String expectedCountry) throws Exception {
    this.mockMvc
        .perform(
            get("/address-lookup-api/multiple-addresses")
                .queryParam("postcode", postcode)
                .header(REQUEST_ID.value(), "request_id"))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$").isArray())
        .andExpect(jsonPath("$.length()").value(greaterThan(0)))
        .andExpect(jsonPath("$[0].postcode").value(expectedPostcode))
        .andExpect(jsonPath("$[0].country").value(expectedCountry));
  }

  @ParameterizedTest
  @MethodSource("addresses")
  void shouldReturnMultipleAddressesIslPostcode(
      String postcode,
      String expectedPostcode,
      String expectedAddressLine1,
      String expectedPostTown,
      String expectedCountry)
      throws Exception {
    this.mockMvc
        .perform(
            get("/address-lookup-api/multiple-addresses")
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

  /** Legacy postcode lookup layouts reported by testers for each component combination. */
  @ParameterizedTest
  @MethodSource("legacyLayouts")
  void shouldSplitPremiseAndAddressLine1LikeLegacyLookup(
      String postcode, String premise, String addressLine1) throws Exception {
    this.mockMvc
        .perform(
            get("/address-lookup-api/multiple-addresses")
                .queryParam("postcode", postcode)
                .header(REQUEST_ID.value(), "request_id"))
        .andExpect(status().isOk())
        .andExpect(
            jsonPath(
                    "$[?(@.premise == '" + premise + "' && @.addressLine1 == '" + addressLine1 + "')]")
                .isNotEmpty());
  }

  // ========================
  // Validation Tests
  // ========================

  @ParameterizedTest
  @ValueSource(
      strings = {
        "   ",
        "A",
        "AB124NYEXTRA",
        "INVALID",
        "ZZZZZZ",
        "INVALID@POSTCODE",
        "12345",
        "LU49P1",
        "' OR '1'='1",
        "<script>alert(1)</script>"
      })
  void shouldReturnEmptyListForInvalidPostcode(String postcode) throws Exception {
    this.mockMvc
        .perform(
            get("/address-lookup-api/multiple-addresses")
                .queryParam("postcode", postcode)
                .header(REQUEST_ID.value(), "request_id"))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$").isEmpty());
  }

  @Test
  void shouldReturnBadRequestForMissingPostcodeParameter() throws Exception {
    this.mockMvc
        .perform(
            get("/address-lookup-api/multiple-addresses")
                .header(REQUEST_ID.value(), "request_id"))
        .andExpect(status().isBadRequest());
  }

  @Test
  void shouldAcceptLowerCasePostcodeWithoutSpace() throws Exception {
    this.mockMvc
        .perform(
            get("/address-lookup-api/multiple-addresses")
                .queryParam("postcode", "ab101au")
                .header(REQUEST_ID.value(), "request_id"))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$[0].postcode").value("AB10 1AU"));
  }

  @ParameterizedTest
  @ValueSource(strings = {" ab101au", "ab10 1au", "AB10 1AU", "ab101au "})
  void shouldReturnEmptyListForPostcodeContainingSpaces(String postcode) throws Exception {
    this.mockMvc
        .perform(
            get("/address-lookup-api/multiple-addresses")
                .queryParam("postcode", postcode)
                .header(REQUEST_ID.value(), "request_id"))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$").isEmpty());
  }

  @Test
  void shouldReturnNotFoundForEmptyPostcode() throws Exception {
    this.mockMvc
        .perform(
            get("/address-lookup-api/multiple-addresses")
                .queryParam("postcode", "")
                .header(REQUEST_ID.value(), "request_id"))
        .andExpect(status().isNotFound());
  }

  @ParameterizedTest
  @ValueSource(strings = {"ZZ11ZZ", "AB124NY", "CF101PX", "LU4s9PJ"})
  void shouldReturnEmptyArrayForUnknownPostcode(String postcode) throws Exception {
    this.mockMvc
        .perform(
            get("/address-lookup-api/multiple-addresses")
                .queryParam("postcode", postcode)
                .header(REQUEST_ID.value(), "request_id"))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$").isArray())
        .andExpect(jsonPath("$").isEmpty());
  }


  @Test
  void shouldVerifyAllAddressesHavePremiseField() throws Exception {
    this.mockMvc
        .perform(
            get("/address-lookup-api/multiple-addresses")
                .queryParam("postcode", "AB101AU")
                .header(REQUEST_ID.value(), "request_id"))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$").isArray())
        .andExpect(jsonPath("$.length()").value(greaterThan(1)))
        .andExpect(jsonPath("$[0].premise").exists())
        .andExpect(jsonPath("$[1].premise").exists())
        .andExpect(jsonPath("$[2].premise").exists());
  }

  private static Stream<Arguments> legacyLayouts() {
    return Stream.of(
        // sub-building and building name, no number
        Arguments.of("LU49PL", "101 GREEN COURT", "GREEN CLOSE"),
        // building name and number, no sub-building
        Arguments.of("CH11DF", "UNIT R2", "33 PEPPER STREET"),
        // sub-building and number, no building name
        Arguments.of("CH11DF", "UNIT R1", "33 PEPPER STREET"),
        Arguments.of("IM11BD", "FLAT 2", "40 PRINCES STREET"),
        Arguments.of("IM11BD", "FLAT 1", "36 PRINCES STREET"));
  }

  private static Stream<Arguments> addresses() {
    return Stream.of(
        Arguments.of("BT100EQ", "BT10 0EQ", "GARRON CRESCENT", "BELFAST", "GB-NIR"),
        Arguments.of("BT513TU", "BT51 3TU", "GRANARY CLOSE", "COLERAINE", "GB-NIR"),
        Arguments.of("GY79AD", "GY7 9AD", "RUE DES HECHES", "GUERNSEY", "Channel Islands"),
        Arguments.of("IM11BD", "IM1 1BD", "2 PRINCES STREET", "ISLE OF MAN", "Isle of Man"),
        Arguments.of("JE27NA", "JE2 7NA", "LES GRANDS VAUX", "JERSEY", "Channel Islands"));
  }
}
