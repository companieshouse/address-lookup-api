package uk.gov.companieshouse.addresslookup.service;

import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import uk.gov.companieshouse.addresslookup.entity.RoyalMailAddressLookup;
import uk.gov.companieshouse.addresslookup.mapper.LegacyAddressMapper;
import uk.gov.companieshouse.addresslookup.mapper.RoyalMailAddressMapper;
import uk.gov.companieshouse.addresslookup.model.LegacyAddress;
import uk.gov.companieshouse.addresslookup.model.RoyalMailAddressDto;
import uk.gov.companieshouse.addresslookup.repository.RoyalMailAddressLookupRepository;

import java.util.List;
import java.util.Locale;
import java.util.Optional;

@Service
public class AddressLookupService {

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

    /**
     * Finds lookup rows for a postcode, ignoring spaces and letter case.
     *
     * <p>For example {@code WF2 7QD}, {@code WF27QD} and {@code wf27qd} all return the same rows.
     * The result comes from the {@code address_lookup.address_lookup} view.
     *
     * @param postcode the postcode as supplied by the caller
     * @return the matching lookup rows, empty if none match
     */
    private List<RoyalMailAddressLookup> findByPostcode(String postcode) {
        // Callers may provide lowercase or spaced postcodes; normalize them to match
        // the repository query's case-insensitive, space-free comparison.
        String normalizedPostcode = postcode.replace(" ", "").toUpperCase(Locale.ROOT);
        return royalMailAddressLookupRepository.findByNormalizedPostcode(normalizedPostcode);
    }

}
