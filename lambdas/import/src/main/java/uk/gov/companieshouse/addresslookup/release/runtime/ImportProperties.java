package uk.gov.companieshouse.addresslookup.release.runtime;

import static uk.gov.companieshouse.addresslookup.lambda.shared.runtime.RuntimeSupport.check;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;

@ConfigurationProperties("importer")
public record ImportProperties(
        String controlBucket,
        String landingBucket,
        String region,
        @DefaultValue("20") int sweepLimit) {
    public ImportProperties {
        check(controlBucket != null && !controlBucket.isBlank(), "Missing CONTROL_BUCKET");
        check(landingBucket != null && !landingBucket.isBlank(), "Missing LANDING_BUCKET");
        check(region != null && !region.isBlank(), "Missing AWS_REGION");
        check(sweepLimit > 0, "Import sweep limit must be positive");
    }
}
