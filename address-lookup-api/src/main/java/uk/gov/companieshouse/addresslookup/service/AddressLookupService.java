package uk.gov.companieshouse.addresslookup.service;

import java.sql.SQLException;
import java.util.List;
import java.util.Optional;
import org.springframework.dao.DataAccessException;
import org.springframework.stereotype.Service;
import uk.gov.companieshouse.addresslookup.entity.RoyalMailAddressLookup;
import uk.gov.companieshouse.addresslookup.mapper.LegacyAddressMapper;
import uk.gov.companieshouse.addresslookup.mapper.RoyalMailAddressMapper;
import uk.gov.companieshouse.addresslookup.model.LegacyAddress;
import uk.gov.companieshouse.addresslookup.model.RoyalMailAddressDto;
import uk.gov.companieshouse.addresslookup.repository.RoyalMailAddressLookupRepository;

/** Database-first lookup with opt-in OS fallback only after a successful empty result. */
@Service
public class AddressLookupService {

    private final RoyalMailAddressLookupRepository royalMailAddressLookupRepository;
    private final LegacyAddressMapper legacyAddressMapper;
    private final RoyalMailAddressMapper royalMailAddressMapper;
    private final OsPlacesClient osPlacesClient;

    public AddressLookupService(RoyalMailAddressLookupRepository royalMailAddressLookupRepository,
            LegacyAddressMapper legacyAddressMapper,
            RoyalMailAddressMapper royalMailAddressMapper,
            OsPlacesClient osPlacesClient) {
        this.royalMailAddressLookupRepository = royalMailAddressLookupRepository;
        this.legacyAddressMapper = legacyAddressMapper;
        this.royalMailAddressMapper = royalMailAddressMapper;
        this.osPlacesClient = osPlacesClient;
    }

    public List<RoyalMailAddressDto> lookupByPostcode(String postcode) {
        var rows = findByPostcode(postcode);
        return rows.isEmpty()
                ? osPlacesClient.lookup(postcode).stream().map(OsPlacesClient.Address::address).toList()
                : royalMailAddressMapper.toDtos(rows);
    }

    public List<LegacyAddress> lookupLegacyAddressesByPostcode(String postcode) {
        var rows = findByPostcode(postcode);
        if (rows.isEmpty()) {
            return osPlacesClient.lookup(postcode).stream()
                    .map(address -> legacyAddressMapper.toLegacyAddress(
                            address.address(), address.country(), true))
                    .toList();
        }
        return rows.stream()
                .map(legacyAddressMapper::toLegacyAddress)
                .toList();
    }

    public Optional<LegacyAddress> lookupLegacyAddressByPostcode(String postcode) {
        var rows = findByPostcode(postcode);
        if (rows.isEmpty()) {
            return osPlacesClient.lookup(postcode).stream().findFirst()
                    .map(address -> legacyAddressMapper.toLegacyAddress(
                            address.address(), address.country(), false));
        }
        return rows.stream()
                .findFirst()
                .map(legacyAddressMapper::toLegacyAddressWithoutPremise);
    }

    /**
     * Retries a lookup once when its database exception chain reports SQLSTATE 40001.
     *
     * @param postcode postcode to look up
     * @return matching address rows
     * @throws DataAccessException when the lookup fails or its single retry fails
     */
    private List<RoyalMailAddressLookup> findByPostcode(String postcode) {
        try {
            return royalMailAddressLookupRepository.findByNormalizedPostcode(postcode);
        } catch (DataAccessException exception) {
            if (!hasSerializationFailure(exception)) {
                throw exception;
            }
            return royalMailAddressLookupRepository.findByNormalizedPostcode(postcode);
        }
    }

    /**
     * Checks nested database causes for the PostgreSQL serialization-failure state.
     *
     * @param exception exception whose cause chain is inspected
     * @return true when the cause chain contains SQLSTATE 40001
     */
    private static boolean hasSerializationFailure(Throwable exception) {
        for (Throwable cause = exception; cause != null; cause = cause.getCause()) {
            if (cause instanceof SQLException sqlException
                    && "40001".equals(sqlException.getSQLState())) {
                return true;
            }
        }
        return false;
    }

}
