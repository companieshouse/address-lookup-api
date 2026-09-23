package uk.gov.companieshouse.addresslookup.controller;

import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.util.List;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import uk.gov.companieshouse.addresslookup.model.LegacyAddress;
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
}