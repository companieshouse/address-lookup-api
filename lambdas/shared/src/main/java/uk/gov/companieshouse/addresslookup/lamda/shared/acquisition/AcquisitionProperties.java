package uk.gov.companieshouse.addresslookup.lamda.shared.acquisition;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;

import java.time.Duration;

import static uk.gov.companieshouse.addresslookup.lamda.shared.runtime.RuntimeSupport.check;

@ConfigurationProperties("acquisition")
public record AcquisitionProperties(
    String sourceBucket,
    String scannedBucket,
    String eventBus,
    String osApiKey,
    String packages,
    @DefaultValue("536870912") long maxZipBytes,
    @DefaultValue("1073741824") long maxExtractedBytes,
    @DefaultValue("20") int sweepLimit,
    @DefaultValue("20s") Duration connectTimeout,
    @DefaultValue("840s") Duration requestTimeout) {
  public AcquisitionProperties {
    check(sourceBucket != null && !sourceBucket.isBlank(), "Missing SOURCE_BUCKET");
    check(maxZipBytes > 0 && maxExtractedBytes > 0 && sweepLimit > 0, "Limits must be positive");
    check(
        !connectTimeout.isNegative()
            && !connectTimeout.isZero()
            && !requestTimeout.isNegative()
            && !requestTimeout.isZero(),
        "Timeouts must be positive");
  }

  // Configuration records must never include injected credentials in logs.
  @Override
  public String toString() {
    return "AcquisitionProperties[credentials redacted]";
  }

  static String required(String value, String name) {
    check(value != null && !value.isBlank(), "Missing " + name);
    return value;
  }
}
