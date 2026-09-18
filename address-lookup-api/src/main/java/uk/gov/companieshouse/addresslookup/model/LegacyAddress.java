package uk.gov.companieshouse.addresslookup.model;

import com.fasterxml.jackson.annotation.JsonInclude;

public record LegacyAddress(
        String postcode,
        @JsonInclude(JsonInclude.Include.NON_NULL)
        String premise,
        String addressLine1,
        String addressLine2,
        String postTown,
        String country) {
}
