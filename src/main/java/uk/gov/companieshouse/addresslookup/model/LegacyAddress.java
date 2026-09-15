package uk.gov.companieshouse.addresslookup.model;

import com.fasterxml.jackson.annotation.JsonInclude;

import io.swagger.v3.oas.annotations.media.Schema;

@Schema(description = "Legacy address format")
public record LegacyAddress(
        @Schema(description = "The postcode of the address", example = "SW1A 1AA")
        String postcode,
        @JsonInclude(JsonInclude.Include.NON_NULL)
        @Schema(description = "The premise of the address", example = "10")
        String premise,
        @Schema(description = "The first line of the address", example = "Downing Street")
        String addressLine1,
        @Schema(description = "The second line of the address", example = "Westminster")
        String addressLine2,
        @Schema(description = "The town/city", example = "London")
        String postTown,
        @Schema(description = "The country of the address", example = "GB-ENG, GB-SCT, GB-WLS, GB-NIR, United Kingdom")
        String country) {
}
