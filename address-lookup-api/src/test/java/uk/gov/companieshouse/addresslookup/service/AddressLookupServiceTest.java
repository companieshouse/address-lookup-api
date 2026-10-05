package uk.gov.companieshouse.addresslookup.service;

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
import uk.gov.companieshouse.addresslookup.repository.RoyalMailAddressLookupRepository;

@ExtendWith(MockitoExtension.class)
class AddressLookupServiceTest {

    @Mock
    private RoyalMailAddressLookupRepository repository;

    @Mock
    private LegacyAddressMapper legacyAddressMapper;

    @Mock
    private RoyalMailAddressMapper royalMailAddressMapper;

    @InjectMocks
    private AddressLookupService service;

    @Test
    void lookupByPostcode_normalizesLowercaseAndSpacesBeforeRepositoryLookup() {
        List<RoyalMailAddressLookup> addresses = List.of();
        when(repository.findByNormalizedPostcode("WF27QD")).thenReturn(addresses);
        when(royalMailAddressMapper.toDtos(addresses)).thenReturn(List.of());

        service.lookupByPostcode("wf2 7qd");

        verify(repository).findByNormalizedPostcode("WF27QD");
    }
}
