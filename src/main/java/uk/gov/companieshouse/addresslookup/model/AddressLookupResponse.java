package uk.gov.companieshouse.addresslookup.model;

import java.util.List;

import io.swagger.v3.oas.annotations.media.Schema;

@Schema(description = "Response containing postcode lookup results")
public record AddressLookupResponse(
    @Schema(description = "The postcode that was searched for", example = "SW1A 1AA")
    String postcode,
    @Schema(description = "Total number of addresses found", example = "3")
    int totalResults,
    @Schema(description = "List of addresses matching the postcode")
    List<RoyalMailAddressDto> addresses) {
}
