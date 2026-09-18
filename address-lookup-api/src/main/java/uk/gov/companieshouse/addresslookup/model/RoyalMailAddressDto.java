package uk.gov.companieshouse.addresslookup.model;

import java.math.BigDecimal;

public record RoyalMailAddressDto(
        Integer udprn,
        Long uprn,
        String organisationName,
        String departmentName,
        String subBuildingName,
        String buildingName,
        Integer buildingNumber,
        String dependentThoroughfare,
        String thoroughfare,
        String doubleDependentLocality,
        String dependentLocality,
        String postTown,
        String postcode,
        String deliveryPointSuffix,
        BigDecimal latitude,
        BigDecimal longitude) {
}
