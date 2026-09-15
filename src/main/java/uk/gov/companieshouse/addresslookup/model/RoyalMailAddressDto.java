package uk.gov.companieshouse.addresslookup.model;

import java.math.BigDecimal;

import io.swagger.v3.oas.annotations.media.Schema;

@Schema(description = "Royal Mail address format")
public record RoyalMailAddressDto(
        @Schema(description = "The Unique Delivery Point Reference Number", example = "12345678")
        Integer udprn,
        @Schema(description = "The Unique Property Reference Number", example = "87654321")
        Long uprn,
        @Schema(description = "Organisation name at the address", example = "Acme Corp")
        String organisationName,
        @Schema(description = "Department name at the address", example = "Sales")
        String departmentName,
        @Schema(description = "The sub-building name of the address", example = "Flat 1")
        String subBuildingName,
        @Schema(description = "The building name of the address", example = "Acme House")
        String buildingName,
        @Schema(description = "The building number of the address", example = "10")
        Integer buildingNumber,
        @Schema(description = "The dependent thoroughfare of the address", example = "Downing Street")
        String dependentThoroughfare,
        @Schema(description = "The thoroughfare of the address", example = "Downing Street")
        String thoroughfare,
        @Schema(description = "The double dependent locality of the address", example = "Westminster")
        String doubleDependentLocality,
        @Schema(description = "The dependent locality of the address", example = "Westminster")
        String dependentLocality,
        @Schema(description = "The post town of the address", example = "London")
        String postTown,
        @Schema(description = "The postcode of the address", example = "SW1A 1AA")
        String postcode,
        @Schema(description = "The delivery point suffix of the address", example = "A")
        String deliveryPointSuffix,
        @Schema(description = "The latitude of the address", example = "51.5034")
        BigDecimal latitude,
        @Schema(description = "The longitude of the address", example = "-0.1276")
        BigDecimal longitude) {
}
