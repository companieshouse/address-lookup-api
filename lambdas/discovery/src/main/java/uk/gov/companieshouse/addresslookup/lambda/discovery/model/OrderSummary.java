package uk.gov.companieshouse.addresslookup.lambda.discovery.model;

import com.fasterxml.jackson.annotation.JsonFormat;

import java.time.LocalDate;

public record OrderSummary(String featureName,
                           String crs,
                           String schemaVersion,
                           @JsonFormat(pattern = "yyyy-MM-dd")
                           LocalDate validFromDate,
                           @JsonFormat(pattern = "yyyy-MM-dd")
                           LocalDate validToDate,
                           long recordCount) {
}
