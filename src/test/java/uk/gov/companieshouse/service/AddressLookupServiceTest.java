package uk.gov.companieshouse.service;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.util.List;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import uk.gov.companieshouse.addresslookup.entity.RoyalMailAddressLookup;
import uk.gov.companieshouse.addresslookup.mapper.LegacyAddressMapper;
import uk.gov.companieshouse.addresslookup.mapper.RoyalMailAddressMapper;
import uk.gov.companieshouse.addresslookup.model.RoyalMailAddressDto;
import uk.gov.companieshouse.addresslookup.repository.RoyalMailAddressLookupRepository;
import uk.gov.companieshouse.addresslookup.service.AddressLookupService;

@ExtendWith(MockitoExtension.class)
class AddressLookupServiceTest {

	@Mock
	private RoyalMailAddressLookupRepository royalMailAddressLookupRepository;

	@Mock
	private LegacyAddressMapper legacyAddressMapper;

	@Mock
	private RoyalMailAddressMapper royalMailAddressMapper;

	@InjectMocks
	private AddressLookupService addressLookupService;

	@Test
	void lookupByPostcode_shouldQueryRepositoryWithSuppliedPostcode() {
		String postcode = "SW1A 1AA";
		List<RoyalMailAddressLookup> addresses = List.of();
		List<RoyalMailAddressDto> expectedAddresses = List.of();
		when(royalMailAddressLookupRepository.findByNormalizedPostcode(postcode)).thenReturn(addresses);
		when(royalMailAddressMapper.toDtos(addresses)).thenReturn(expectedAddresses);

		List<RoyalMailAddressDto> result = addressLookupService.lookupByPostcode(postcode);

		assertEquals(expectedAddresses, result);
		verify(royalMailAddressLookupRepository).findByNormalizedPostcode(postcode);
	}
}
