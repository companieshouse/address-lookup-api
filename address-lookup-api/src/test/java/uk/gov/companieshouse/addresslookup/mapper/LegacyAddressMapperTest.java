package uk.gov.companieshouse.addresslookup.mapper;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertAll;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import org.junit.jupiter.api.Test;
import org.mapstruct.factory.Mappers;
import uk.gov.companieshouse.addresslookup.entity.RoyalMailAddressLookup;

class LegacyAddressMapperTest {

    private final LegacyAddressMapper mapper = Mappers.getMapper(LegacyAddressMapper.class);

    @Test
    void premiseIncludesSubBuildingAndBuildingNumber() {
        RoyalMailAddressLookup address = mock(RoyalMailAddressLookup.class);
        when(address.getSubBuildingName()).thenReturn("FLAT 1");
        when(address.getBuildingNumber()).thenReturn(16);

        assertEquals("FLAT 1, 16", mapper.toPremise(address));
    }

    @Test
    void premiseUsesOnlyTheBuildingNumberWhenSubBuildingIsAbsent() {
        RoyalMailAddressLookup address = mock(RoyalMailAddressLookup.class);
        when(address.getSubBuildingName()).thenReturn(null);
        when(address.getBuildingNumber()).thenReturn(14);

        assertEquals("14", mapper.toPremise(address));
    }

    @Test
    void premiseUsesOnlyTheSubBuildingWhenBuildingNumberIsAbsent() {
        RoyalMailAddressLookup address = mock(RoyalMailAddressLookup.class);
        when(address.getSubBuildingName()).thenReturn("FLAT 1");
        when(address.getBuildingNumber()).thenReturn(null);

        assertEquals("FLAT 1", mapper.toPremise(address));
    }

    @Test
    void premiseIsEmptyWhenBothComponentsAreAbsent() {
        RoyalMailAddressLookup address = mock(RoyalMailAddressLookup.class);
        when(address.getSubBuildingName()).thenReturn(null);
        when(address.getBuildingNumber()).thenReturn(null);

        assertEquals("", mapper.toPremise(address));
    }

    @Test
    void legacyAddressMappingIncludesPremise() {
        RoyalMailAddressLookup address = mock(RoyalMailAddressLookup.class);
        when(address.getSubBuildingName()).thenReturn("FLAT 1");
        when(address.getBuildingNumber()).thenReturn(16);

        assertEquals("FLAT 1, 16", mapper.toLegacyAddress(address).premise());
    }

    @Test
    void legacyAddressMappingWithoutPremiseLeavesItNull() {
        RoyalMailAddressLookup address = mock(RoyalMailAddressLookup.class);

        assertNull(mapper.toLegacyAddressWithoutPremise(address).premise());
    }

    @Test
    void legacyAddressMappingReturnsNullForNullInput() {
        assertAll(
                () -> assertNull(mapper.toLegacyAddress(null)),
                () -> assertNull(mapper.toLegacyAddressWithoutPremise(null)));
    }

    @Test
    void joinTrimsPartsAndOmitsBlankValues() {
        assertEquals("Main Road, High Street", mapper.join(" Main Road ", " ", "High Street"));
    }

    @Test
    void countryMappingsPreserveLegacyCodesAndFallbacks() {
        assertAll(
                () -> assertEquals("GB-ENG", mapper.toLegacyCountry("England")),
                () -> assertEquals("GB-SCT", mapper.toLegacyCountry("Scotland")),
                () -> assertEquals("GB-WLS", mapper.toLegacyCountry("Wales")),
                () -> assertEquals("GB-NIR", mapper.toLegacyCountry("Northern Ireland")),
                () -> assertEquals("United Kingdom", mapper.toLegacyCountry(null)),
                () -> assertEquals("United Kingdom", mapper.toLegacyCountry(" ")),
                () -> assertEquals("Canada", mapper.toLegacyCountry("Canada")));
    }
}
