package uk.gov.companieshouse.addresslookup.lambda.download.model;

public record VersionCursor(
        String dataPackageId, String lastProcessedVersionId, String lastProcessedDate) {}
