package uk.gov.companieshouse.addresslookup.service;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.sql.SQLException;
import java.util.List;
import java.util.Optional;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.dao.DataAccessException;
import org.springframework.dao.DataAccessResourceFailureException;
import uk.gov.companieshouse.addresslookup.entity.RoyalMailAddressLookup;
import uk.gov.companieshouse.addresslookup.mapper.LegacyAddressMapper;
import uk.gov.companieshouse.addresslookup.mapper.RoyalMailAddressMapper;
import uk.gov.companieshouse.addresslookup.model.LegacyAddress;
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
    void lookupByPostcodeMapsTheRepositoryResults() {
        when(repository.findByNormalizedPostcode("AB1 2CD")).thenReturn(List.of());
        when(royalMailAddressMapper.toDtos(List.of())).thenReturn(List.of());

        assertEquals(List.of(), service.lookupByPostcode("AB1 2CD"));

        verify(royalMailAddressMapper).toDtos(List.of());
    }

    @Test
    void lookupLegacyAddressesMapsEachRepositoryResult() {
        RoyalMailAddressLookup address = mock(RoyalMailAddressLookup.class);
        LegacyAddress expected =
                new LegacyAddress("AB1 2CD", "Address", null, null, "Town", "GB-ENG");
        when(repository.findByNormalizedPostcode("AB1 2CD")).thenReturn(List.of(address));
        when(legacyAddressMapper.toLegacyAddress(address)).thenReturn(expected);

        assertEquals(List.of(expected), service.lookupLegacyAddressesByPostcode("AB1 2CD"));
    }

    @Test
    void lookupLegacyAddressMapsTheFirstResult() {
        RoyalMailAddressLookup address = mock(RoyalMailAddressLookup.class);
        LegacyAddress expected =
                new LegacyAddress("AB1 2CD", "Address", null, null, "Town", "GB-ENG");
        when(repository.findByNormalizedPostcode("AB1 2CD")).thenReturn(List.of(address));
        when(legacyAddressMapper.toLegacyAddressWithoutPremise(address)).thenReturn(expected);

        assertEquals(Optional.of(expected), service.lookupLegacyAddressByPostcode("AB1 2CD"));
    }

    @Test
    void lookupLegacyAddressReturnsEmptyWhenNoResultsExist() {
        when(repository.findByNormalizedPostcode("AB1 2CD")).thenReturn(List.of());

        assertEquals(Optional.empty(), service.lookupLegacyAddressByPostcode("AB1 2CD"));
    }

    @Test
    void retriesSerializationFailureOnceAndReturnsSuccessfulResult() {
        DataAccessException serializationFailure = dataAccessFailure("40001");
        when(repository.findByNormalizedPostcode("AB1 2CD"))
                .thenThrow(serializationFailure)
                .thenReturn(List.of());

        assertEquals(List.of(), service.lookupLegacyAddressesByPostcode("AB1 2CD"));

        verify(repository, times(2)).findByNormalizedPostcode("AB1 2CD");
    }

    @Test
    void propagatesSecondSerializationFailureWithoutFurtherRetry() {
        DataAccessException firstFailure = dataAccessFailure("40001");
        DataAccessException secondFailure = dataAccessFailure("40001");
        when(repository.findByNormalizedPostcode("AB1 2CD"))
                .thenThrow(firstFailure)
                .thenThrow(secondFailure);

        DataAccessException thrown = assertThrows(
                DataAccessException.class,
                () -> service.lookupLegacyAddressesByPostcode("AB1 2CD"));

        assertSame(secondFailure, thrown);
        verify(repository, times(2)).findByNormalizedPostcode("AB1 2CD");
    }

    @Test
    void doesNotRetryAnUnrelatedSqlState() {
        DataAccessException failure = dataAccessFailure("23505");
        when(repository.findByNormalizedPostcode("AB1 2CD")).thenThrow(failure);

        DataAccessException thrown = assertThrows(
                DataAccessException.class,
                () -> service.lookupLegacyAddressesByPostcode("AB1 2CD"));

        assertSame(failure, thrown);
        verify(repository, times(1)).findByNormalizedPostcode("AB1 2CD");
    }

    private static DataAccessException dataAccessFailure(String sqlState) {
        SQLException sqlException = new SQLException("database failure", sqlState);
        return new DataAccessResourceFailureException(
                "database operation failed", new IllegalStateException(sqlException));
    }
}
