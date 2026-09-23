package uk.gov.companieshouse.addresslookup.release.runtime;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;

import static uk.gov.companieshouse.addresslookup.lamda.shared.runtime.RuntimeSupport.check;

@ConfigurationProperties("importer")
public record ImportProperties(
    String scannedBucket, String region, @DefaultValue("20") int sweepLimit) {
  public ImportProperties {
    check(scannedBucket != null && !scannedBucket.isBlank(), "Missing SCANNED_BUCKET");
    check(region != null && !region.isBlank(), "Missing AWS_REGION");
    check(sweepLimit > 0, "Import sweep limit must be positive");
  }
}
