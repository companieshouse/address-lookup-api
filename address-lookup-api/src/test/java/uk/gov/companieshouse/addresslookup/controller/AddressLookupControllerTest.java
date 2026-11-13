package uk.gov.companieshouse.addresslookup.controller;

import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.math.BigDecimal;
import java.util.List;
import java.util.Optional;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import uk.gov.companieshouse.addresslookup.model.LegacyAddress;
import uk.gov.companieshouse.addresslookup.model.RoyalMailAddressDto;
import uk.gov.companieshouse.addresslookup.service.AddressLookupService;

@ExtendWith(MockitoExtension.class)
class AddressLookupControllerTest {

  @Mock private AddressLookupService addressLookupService;

  @InjectMocks private AddressLookupController controller;

  private MockMvc mockMvc;

  @BeforeEach
  void setUp() {
    mockMvc = MockMvcBuilders.standaloneSetup(controller).build();
  }

  @Test
  void multipleAddresses_shouldReturnAddressesForPostcode() throws Exception {
    when(addressLookupService.lookupLegacyAddressesByPostcode("SW1A1AA"))
        .thenReturn(
            List.of(
                new LegacyAddress(
                    "SW1A 1AA", "10 Downing Street", "Whitehall", null, "London", "GB-ENG")));

    mockMvc
        .perform(get("/address-lookup-api/multiple-addresses").queryParam("postcode", "SW1A1AA"))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$[0].postcode").value("SW1A 1AA"));
  }

  @ParameterizedTest
  @ValueSource(strings = {"addresses", "multiple-addresses", "postcode"})
  void shouldReturnNotFoundForEmptyPostcode(String path) throws Exception {
    mockMvc
        .perform(get("/address-lookup-api/" + path).queryParam("postcode", ""))
        .andExpect(status().isNotFound());
    verifyNoInteractions(addressLookupService);
  }

  @Test
  void addresses_shouldReturnStructuredResponseForPostcode() throws Exception {
    when(addressLookupService.lookupByPostcode("SW1A1AA"))
        .thenReturn(
            List.of(
                new RoyalMailAddressDto(
                    123,
                    456L,
                    "Company",
                    "Department",
                    "Flat 1",
                    "Building",
                    16,
                    "Old Road",
                    "Main Road",
                    "Village",
                    "District",
                    "London",
                    "SW1A 1AA",
                    "1A",
                    new BigDecimal("51.5000"),
                    new BigDecimal("-0.1000"))));

    mockMvc
        .perform(get("/address-lookup-api/addresses").queryParam("postcode", "SW1A1AA"))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.postcode").value("SW1A1AA"))
        .andExpect(jsonPath("$.totalResults").value(1))
        .andExpect(jsonPath("$.addresses[0].udprn").value(123))
        .andExpect(jsonPath("$.addresses[0].latitude").value(51.5));
  }

  @Test
  void postcode_shouldReturnLegacyAddressWhenFound() throws Exception {
    when(addressLookupService.lookupLegacyAddressByPostcode("SW1A1AA"))
        .thenReturn(
            Optional.of(
                new LegacyAddress("SW1A 1AA", "10", "Downing Street", null, "London", "GB-ENG")));

    mockMvc
        .perform(get("/address-lookup-api/postcode").queryParam("postcode", "SW1A1AA"))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.premise").value("10"))
        .andExpect(jsonPath("$.country").value("GB-ENG"));
  }

  @Test
  void postcode_shouldReturnNotFoundWhenNoAddressIsFound() throws Exception {
    when(addressLookupService.lookupLegacyAddressByPostcode("ZZ11ZZ"))
        .thenReturn(Optional.empty());

    mockMvc
        .perform(get("/address-lookup-api/postcode").queryParam("postcode", "ZZ11ZZ"))
        .andExpect(status().isNotFound());
  }

  @ParameterizedTest
  @ValueSource(strings = {"addresses"})
  void shouldReturnBadRequestForInvalidPostcode(String path) throws Exception {
    mockMvc
        .perform(get("/address-lookup-api/" + path).queryParam("postcode", "12345"))
        .andExpect(status().isBadRequest());
    verifyNoInteractions(addressLookupService);
  }

  @ParameterizedTest
  @ValueSource(strings = {"12345", "SW1A@1AA", " SW1A1AA", "SW1A 1AA", "SW1A1AAX"})
  void postcode_shouldReturnNotFoundForInvalidPostcode(String postcode) throws Exception {
    mockMvc
        .perform(get("/address-lookup-api/postcode").queryParam("postcode", postcode))
        .andExpect(status().isNotFound());
    verifyNoInteractions(addressLookupService);
  }

  @ParameterizedTest
  @ValueSource(strings = {" SW1A1AA", "SW1A 1AA", "SW1A1AA "})
  void addresses_shouldReturnEmptyResultsForPostcodeContainingSpaces(String postcode)
      throws Exception {
    mockMvc
        .perform(get("/address-lookup-api/addresses").queryParam("postcode", postcode))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.totalResults").value(0))
        .andExpect(jsonPath("$.addresses").isEmpty());
    verifyNoInteractions(addressLookupService);
  }

  @ParameterizedTest
  @ValueSource(strings = {" SW1A1AA", "SW1A 1AA", "SW1A1AA ", "12345", "SW1A@1AA", "SW1A1AAX"})
  void multipleAddresses_shouldReturnEmptyListForInvalidPostcode(String postcode)
      throws Exception {
    mockMvc
        .perform(get("/address-lookup-api/multiple-addresses").queryParam("postcode", postcode))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$").isEmpty());
    verifyNoInteractions(addressLookupService);
  }
}
