package uk.gov.companieshouse.addresslookup.mapper;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertAll;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import org.junit.jupiter.api.Test;
import org.mapstruct.factory.Mappers;
import uk.gov.companieshouse.addresslookup.entity.RoyalMailAddressLookup;
import uk.gov.companieshouse.addresslookup.model.LegacyAddress;

class LegacyAddressMapperTest {

    private final LegacyAddressMapper mapper = Mappers.getMapper(LegacyAddressMapper.class);

    @Test
    void premiseIncludesSubBuildingAndBuildingNumber() {
        RoyalMailAddressLookup address = mock(RoyalMailAddressLookup.class);
        when(address.getSubBuildingName()).thenReturn("FLAT 1");
        when(address.getBuildingNumber()).thenReturn(16);

        assertEquals("FLAT 1 16", mapper.toPremise(address));
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
    void premiseIncludesBuildingName() {
        RoyalMailAddressLookup address = mock(RoyalMailAddressLookup.class);
        when(address.getSubBuildingName()).thenReturn("FLAT 1");
        when(address.getBuildingName()).thenReturn("SOVEREIGN HOUSE");
        when(address.getBuildingNumber()).thenReturn(16);

        assertEquals("FLAT 1 SOVEREIGN HOUSE 16", mapper.toPremise(address));
        when(address.getSubBuildingName()).thenReturn(null);
        when(address.getBuildingNumber()).thenReturn(null);
        assertEquals("SOVEREIGN HOUSE", mapper.toPremise(address));
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

        assertEquals("FLAT 1 16", mapper.toLegacyAddress(address).premise());
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
    void legacyAddressMappingJoinsThoroughfareAndLocalityLinesWithSpaces() {
        RoyalMailAddressLookup address = mock(RoyalMailAddressLookup.class);
        when(address.getDependentThoroughfare()).thenReturn("MILL LANE");
        when(address.getThoroughfare()).thenReturn("HIGH STREET");
        when(address.getDoubleDependentLocality()).thenReturn("OLD TOWN");
        when(address.getDependentLocality()).thenReturn("RIVERSIDE");

        LegacyAddress legacy = mapper.toLegacyAddress(address);
        LegacyAddress withoutPremise = mapper.toLegacyAddressWithoutPremise(address);

        assertAll(
                () -> assertEquals("MILL LANE HIGH STREET", legacy.addressLine1()),
                () -> assertEquals("OLD TOWN RIVERSIDE", legacy.addressLine2()),
                () -> assertEquals("MILL LANE HIGH STREET", withoutPremise.addressLine1()),
                () -> assertEquals("OLD TOWN RIVERSIDE", withoutPremise.addressLine2()));
    }

    @Test
    void legacyAddressLinesAreEmptyWhenComponentsAreAbsent() {
        RoyalMailAddressLookup address = mock(RoyalMailAddressLookup.class);

        LegacyAddress legacy = mapper.toLegacyAddress(address);

        assertAll(
                () -> assertEquals("", legacy.addressLine1()),
                () -> assertEquals("", legacy.addressLine2()));
    }

    @Test
    void joinTrimsPartsAndOmitsBlankValues() {
        assertEquals("Main Road High Street", mapper.join(" Main Road ", " ", "High Street"));
    }

    @Test
    void joinHandlesSingleNullEmptyAndAllBlankInputs() {
        assertAll(
                () -> assertEquals("Main Road", mapper.join("Main Road", null)),
                () -> assertEquals("Main Road", mapper.join(null, "", "Main Road")),
                () -> assertEquals("", mapper.join(null, null)),
                () -> assertEquals("", mapper.join(" ", "")),
                () -> assertEquals("", mapper.join()));
    }

    @Test
    void joinKeepsPunctuationInsideParts() {
        assertEquals("FLAT 1, BLOCK A 16", mapper.join("FLAT 1, BLOCK A", "16"));
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
