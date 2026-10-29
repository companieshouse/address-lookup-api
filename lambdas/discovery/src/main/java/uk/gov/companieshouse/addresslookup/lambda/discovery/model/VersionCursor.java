package uk.gov.companieshouse.addresslookup.lambda.discovery.model;


public record VersionCursor(String dataPackageId, String lastProcessedVersionId,
                             String lastProcessedDate) {
}

