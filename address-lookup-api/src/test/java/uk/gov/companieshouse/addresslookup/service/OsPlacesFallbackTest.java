package uk.gov.companieshouse.addresslookup.service;

import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.Mockito.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

import java.math.BigDecimal;
import java.util.List;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mapstruct.factory.Mappers;
import org.springframework.dao.DataAccessResourceFailureException;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import uk.gov.companieshouse.addresslookup.controller.AddressLookupController;
import uk.gov.companieshouse.addresslookup.entity.RoyalMailAddressLookup;
import uk.gov.companieshouse.addresslookup.mapper.LegacyAddressMapper;
import uk.gov.companieshouse.addresslookup.mapper.RoyalMailAddressMapper;
import uk.gov.companieshouse.addresslookup.model.RoyalMailAddressDto;
import uk.gov.companieshouse.addresslookup.repository.RoyalMailAddressLookupRepository;

class OsPlacesFallbackTest {
  private final RoyalMailAddressLookupRepository repository =
      mock(RoyalMailAddressLookupRepository.class);
  private final OsPlacesClient places = mock(OsPlacesClient.class);
  private final AddressLookupService service =
      new AddressLookupService(
          repository,
          Mappers.getMapper(LegacyAddressMapper.class),
          Mappers.getMapper(RoyalMailAddressMapper.class),
          places);
  private MockMvc mvc;

  @BeforeEach
  void setUp() {
    mvc = MockMvcBuilders.standaloneSetup(new AddressLookupController(service)).build();
  }

  @Test
  void missingDatabaseAddressUsesExistingLegacyAndStructuredPayloads() throws Exception {
    when(repository.findByNormalizedPostcode("CF143AT")).thenReturn(List.of());
    var dto =
        new RoyalMailAddressDto(
            4213550,
            10002513014L,
            null,
            null,
            "FLAT 1",
            "SOURCE HOUSE",
            12,
            null,
            "SOURCE ROAD",
            null,
            null,
            "CARDIFF",
            "CF14 3AT",
            "1A",
            new BigDecimal("51.5"),
            new BigDecimal("-3.2"));
    when(places.lookup("CF143AT")).thenReturn(List.of(new OsPlacesClient.Address(dto, "Wales")));

    mvc.perform(get("/address-lookup-api/multiple-addresses").param("postcode", "CF143AT"))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.length()").value(1))
        .andExpect(jsonPath("$[0].length()").value(6))
        .andExpect(jsonPath("$[0].premise").value("FLAT 1 SOURCE HOUSE 12"))
        .andExpect(jsonPath("$[0].addressLine1").value("SOURCE ROAD"))
        .andExpect(jsonPath("$[0].addressLine2").value(""))
        .andExpect(jsonPath("$[0].country").value("GB-WLS"))
        .andExpect(jsonPath("$[0].DPA").doesNotExist());
    mvc.perform(get("/address-lookup-api/addresses").param("postcode", "CF143AT"))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.totalResults").value(1))
        .andExpect(jsonPath("$.addresses[0].udprn").value(4213550))
        .andExpect(jsonPath("$.addresses[0].latitude").value(51.5))
        .andExpect(jsonPath("$.header").doesNotExist());
    mvc.perform(get("/address-lookup-api/postcode").param("postcode", "CF143AT"))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.premise").doesNotExist())
        .andExpect(jsonPath("$.addressLine1").value("SOURCE ROAD"));
  }

  @Test
  void existingCsvResultsNeverCallPlaces() throws Exception {
    RoyalMailAddressLookup row = mock(RoyalMailAddressLookup.class);
    when(row.getPostcode()).thenReturn("CF14 3AT");
    when(repository.findByNormalizedPostcode("CF143AT")).thenReturn(List.of(row));
    mvc.perform(get("/address-lookup-api/multiple-addresses").param("postcode", "CF143AT"))
        .andExpect(status().isOk());
    service.lookupByPostcode("CF143AT");
    service.lookupLegacyAddressByPostcode("CF143AT");
    verifyNoInteractions(places);
  }

  @Test
  void databaseErrorsDoNotTriggerFallback() {
    when(repository.findByNormalizedPostcode("CF143AT"))
        .thenThrow(new DataAccessResourceFailureException("database unavailable"));
    assertThrows(
        DataAccessResourceFailureException.class,
        () -> service.lookupLegacyAddressesByPostcode("CF143AT"));
    verifyNoInteractions(places);
  }

  @Test
  void missingInBothSourcesPreservesExistingEmptyAndNotFoundContracts() throws Exception {
    when(repository.findByNormalizedPostcode("CF101PX")).thenReturn(List.of());
    when(places.lookup("CF101PX")).thenReturn(List.of());
    mvc.perform(get("/address-lookup-api/multiple-addresses").param("postcode", "CF101PX"))
        .andExpect(status().isOk())
        .andExpect(content().json("[]"));
    mvc.perform(get("/address-lookup-api/addresses").param("postcode", "CF101PX"))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.totalResults").value(0));
    mvc.perform(get("/address-lookup-api/postcode").param("postcode", "CF101PX"))
        .andExpect(status().isNotFound());
  }
}
