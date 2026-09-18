package uk.gov.companieshouse.addresslookup.model;

import java.util.List;

public record AddressLookupResponse(String postcode, int totalResults, List<RoyalMailAddressDto> addresses) {
}
