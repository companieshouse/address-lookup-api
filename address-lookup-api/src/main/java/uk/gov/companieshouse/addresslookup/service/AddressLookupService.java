package uk.gov.companieshouse.addresslookup.service;

import java.util.List;
import java.util.Locale;
import java.util.Optional;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.web.server.ResponseStatusException;
import uk.gov.companieshouse.addresslookup.entity.RoyalMailAddressLookup;
import uk.gov.companieshouse.addresslookup.mapper.LegacyAddressMapper;
import uk.gov.companieshouse.addresslookup.mapper.RoyalMailAddressMapper;
import uk.gov.companieshouse.addresslookup.model.LegacyAddress;
import uk.gov.companieshouse.addresslookup.model.RoyalMailAddressDto;
import uk.gov.companieshouse.addresslookup.repository.RoyalMailAddressLookupRepository;

@Service
public class AddressLookupService {

    private static final int MAX_POSTCODE_LENGTH = 8;

    private final RoyalMailAddressLookupRepository royalMailAddressLookupRepository;
    private final LegacyAddressMapper legacyAddressMapper;
    private final RoyalMailAddressMapper royalMailAddressMapper;

    public AddressLookupService(RoyalMailAddressLookupRepository royalMailAddressLookupRepository,
            LegacyAddressMapper legacyAddressMapper,
            RoyalMailAddressMapper royalMailAddressMapper) {
        this.royalMailAddressLookupRepository = royalMailAddressLookupRepository;
        this.legacyAddressMapper = legacyAddressMapper;
        this.royalMailAddressMapper = royalMailAddressMapper;
    }

    public List<RoyalMailAddressDto> lookupByPostcode(String postcode) {
        return royalMailAddressMapper.toDtos(findByPostcode(postcode));
    }

    public List<LegacyAddress> lookupLegacyAddressesByPostcode(String postcode) {
        return findByPostcode(postcode).stream()
                .map(legacyAddressMapper::toLegacyAddress)
                .toList();
    }

    public Optional<LegacyAddress> lookupLegacyAddressByPostcode(String postcode) {
        return findByPostcode(postcode).stream()
                .findFirst()
                .map(legacyAddressMapper::toLegacyAddressWithoutPremise);
    }

    private String normalizePostcode(String postcode) {
        return postcode.replaceAll("\\s+", "").toUpperCase(Locale.ROOT);
    }

    private List<RoyalMailAddressLookup> findByPostcode(String postcode) {
        String normalizedPostcode = normalizePostcode(postcode);
        if (normalizedPostcode.length() > MAX_POSTCODE_LENGTH) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Postcode must be 8 characters or fewer");
        }

        return royalMailAddressLookupRepository.findByNormalizedPostcode(normalizedPostcode);
    }

}
