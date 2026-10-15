package uk.gov.companieshouse.addresslookup.mapper;

import static org.junit.jupiter.api.Assertions.assertAll;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.springframework.test.util.ReflectionTestUtils.setField;

import java.math.BigDecimal;
import java.util.Arrays;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.mapstruct.factory.Mappers;
import uk.gov.companieshouse.addresslookup.entity.RoyalMailAddressLookup;
import uk.gov.companieshouse.addresslookup.model.RoyalMailAddressDto;

class RoyalMailAddressMapperTest {

    private final RoyalMailAddressMapper mapper = Mappers.getMapper(RoyalMailAddressMapper.class);

    @Test
    void mapsAddressFieldsAndHandlesNullInputs() {
        RoyalMailAddressLookup address = new RoyalMailAddressLookup();
        setField(address, "id", "address-id");
        setField(address, "addressSource", "royal-mail");
        setField(address, "udprn", 123);
        setField(address, "uprn", 456L);
        setField(address, "organisationName", "Company");
        setField(address, "departmentName", "Department");
        setField(address, "subBuildingName", "Flat 1");
        setField(address, "buildingName", "Building");
        setField(address, "buildingNumber", 16);
        setField(address, "dependentThoroughfare", "Old Road");
        setField(address, "thoroughfare", "Main Road");
        setField(address, "doubleDependentLocality", "Village");
        setField(address, "dependentLocality", "District");
        setField(address, "postTown", "London");
        setField(address, "postcode", "SW1A 1AA");
        setField(address, "deliveryPointSuffix", "1A");
        setField(address, "latitude", new BigDecimal("51.5000"));
        setField(address, "longitude", new BigDecimal("-0.1000"));
        setField(address, "country", "England");

        RoyalMailAddressDto expected = new RoyalMailAddressDto(
                123, 456L, "Company", "Department", "Flat 1", "Building", 16,
                "Old Road", "Main Road", "Village", "District", "London",
                "SW1A 1AA", "1A", new BigDecimal("51.5000"), new BigDecimal("-0.1000"));

        assertAll(
                () -> assertEquals("address-id", address.getId()),
                () -> assertEquals("royal-mail", address.getAddressSource()),
                () -> assertEquals("England", address.getCountry()),
                () -> assertEquals(expected, mapper.toDto(address)),
                () -> assertEquals(Arrays.asList(expected, null), mapper.toDtos(Arrays.asList(address, null))),
                () -> assertNull(mapper.toDto(null)),
                () -> assertNull(mapper.toDtos(null)));
    }
}
