package uk.gov.companieshouse.addresslookup.controller;

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

    @Mock
    private AddressLookupService addressLookupService;

    @InjectMocks
    private AddressLookupController controller;

    private MockMvc mockMvc;

    @BeforeEach
    void setUp() {
        mockMvc = MockMvcBuilders.standaloneSetup(controller).build();
    }

    @Test
    void multipleAddresses_shouldReturnAddressesForPostcode() throws Exception {
        when(addressLookupService.lookupLegacyAddressesByPostcode("SW1A 1AA"))
                .thenReturn(List.of(new LegacyAddress(
                        "SW1A 1AA", "10 Downing Street", "Whitehall", null, "London", "GB-ENG")));

        mockMvc.perform(get("/address-lookup-api/multiple-addresses")
                        .queryParam("postcode", "SW1A 1AA"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$[0].postcode").value("SW1A 1AA"));
    }

    @Test
    void multipleAddresses_shouldReturnNotFoundForEmptyPostcode() throws Exception {
        mockMvc.perform(get("/address-lookup-api/multiple-addresses")
                        .queryParam("postcode", ""))
                .andExpect(status().isNotFound());
    }

    @Test
    void addresses_shouldReturnStructuredResponseForPostcode() throws Exception {
        when(addressLookupService.lookupByPostcode("SW1A 1AA"))
                .thenReturn(List.of(new RoyalMailAddressDto(
                        123, 456L, "Company", "Department", "Flat 1", "Building", 16,
                        "Old Road", "Main Road", "Village", "District", "London",
                        "SW1A 1AA", "1A", new BigDecimal("51.5000"), new BigDecimal("-0.1000"))));

        mockMvc.perform(get("/address-lookup-api/addresses")
                        .queryParam("postcode", "SW1A 1AA"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.postcode").value("SW1A 1AA"))
                .andExpect(jsonPath("$.totalResults").value(1))
                .andExpect(jsonPath("$.addresses[0].udprn").value(123))
                .andExpect(jsonPath("$.addresses[0].latitude").value(51.5));
    }

    @Test
    void postcode_shouldReturnLegacyAddressWhenFound() throws Exception {
        when(addressLookupService.lookupLegacyAddressByPostcode("SW1A 1AA"))
                .thenReturn(Optional.of(new LegacyAddress(
                        "SW1A 1AA", "10", "Downing Street", null, "London", "GB-ENG")));

        mockMvc.perform(get("/address-lookup-api/postcode")
                        .queryParam("postcode", "SW1A 1AA"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.premise").value("10"))
                .andExpect(jsonPath("$.country").value("GB-ENG"));
    }

    @Test
    void postcode_shouldReturnNotFoundWhenNoAddressIsFound() throws Exception {
        when(addressLookupService.lookupLegacyAddressByPostcode("UNKNOWN"))
                .thenReturn(Optional.empty());

        mockMvc.perform(get("/address-lookup-api/postcode")
                        .queryParam("postcode", "UNKNOWN"))
                .andExpect(status().isNotFound());
    }
}