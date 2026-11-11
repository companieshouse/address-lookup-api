package uk.gov.companieshouse.addresslookup.lambda.upload.runtime;

import static uk.gov.companieshouse.addresslookup.lambda.shared.runtime.RuntimeSupport.check;

import org.springframework.boot.context.properties.ConfigurationProperties;

/** Manifests are read from the ingest bucket; scanned CSVs from the scanned bucket. */
@ConfigurationProperties("upload")
public record UploadProperties(String scannedBucket, String ingestBucket) {
  public UploadProperties {
    check(scannedBucket != null && !scannedBucket.isBlank(), "Missing SCANNED_BUCKET");
    check(ingestBucket != null && !ingestBucket.isBlank(), "Missing INGEST_BUCKET");
  }
}
