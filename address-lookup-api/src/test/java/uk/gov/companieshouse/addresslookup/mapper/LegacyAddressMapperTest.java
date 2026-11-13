package uk.gov.companieshouse.addresslookup.mapper;

import static org.junit.jupiter.api.Assertions.assertAll;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.mockito.Mockito.when;

import java.util.stream.Stream;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;
import org.mapstruct.factory.Mappers;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import uk.gov.companieshouse.addresslookup.entity.RoyalMailAddressLookup;
import uk.gov.companieshouse.addresslookup.model.LegacyAddress;

@ExtendWith(MockitoExtension.class)
class LegacyAddressMapperTest {

  private final LegacyAddressMapper mapper = Mappers.getMapper(LegacyAddressMapper.class);

  @Mock
  private RoyalMailAddressLookup address;

  static Stream<Arguments> layouts() {
    return Stream.of(
        // sub, name, number, expected premise, expected line 1
        Arguments.of(null, null, null, "", "HIGH STREET"),
        Arguments.of(null, null, 14, "14", "HIGH STREET"),
        Arguments.of(null, "UNIT R2", null, "UNIT R2", "HIGH STREET"),
        Arguments.of(null, "UNIT R2", 33, "UNIT R2", "33 HIGH STREET"),
        Arguments.of("FLAT 2", null, null, "FLAT 2", "HIGH STREET"),
        Arguments.of("FLAT 2", null, 40, "FLAT 2", "40 HIGH STREET"),
        Arguments.of("101", "GREEN COURT", null, "101 GREEN COURT", "HIGH STREET"),
        Arguments.of("FLAT 1", "SOVEREIGN HOUSE", 16, "FLAT 1 SOVEREIGN HOUSE", "16 HIGH STREET"));
  }

  @ParameterizedTest
  @MethodSource("layouts")
  void mapsPremiseAndAddressLine1ByComponentCombination(
      String sub, String name, Integer number, String premise, String line1) {
    when(address.getSubBuildingName()).thenReturn(sub);
    when(address.getBuildingName()).thenReturn(name);
    when(address.getBuildingNumber()).thenReturn(number);
    when(address.getThoroughfare()).thenReturn("HIGH STREET");

    LegacyAddress legacy = mapper.toLegacyAddress(address);

    assertAll(
        () -> assertEquals(premise, legacy.premise()),
        () -> assertEquals(line1, legacy.addressLine1()));
  }

  @Test
  void premiseIsEmptyWhenBothComponentsAreAbsent() {
    when(address.getSubBuildingName()).thenReturn(null);
    when(address.getBuildingNumber()).thenReturn(null);

    assertEquals("", mapper.toPremise(address));
  }

  @Test
  void legacyAddressMappingIncludesPremise() {
    when(address.getBuildingName()).thenReturn("SOVEREIGN HOUSE");

    assertEquals("SOVEREIGN HOUSE", mapper.toLegacyAddress(address).premise());
  }

  @Test
  void legacyAddressMappingWithoutPremiseLeavesItNull() {
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
