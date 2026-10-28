package uk.gov.companieshouse.addresslookup.lambda.shared.acquisition;

import static uk.gov.companieshouse.addresslookup.lambda.shared.runtime.RuntimeSupport.check;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;
import uk.gov.companieshouse.release.model.DatasetDefault;

import java.time.Duration;
import java.util.List;

@ConfigurationProperties("acquisition")
public record AcquisitionProperties(
    String sourceBucket,
    String scannedBucket,
    String ingestBucket,
    String eventBus,
    String osApiKey,
    String packages,
    @DefaultValue("21474836480") long maxZipBytes,
    List<DatasetDefault> datasets,
    @DefaultValue("https://api.os.uk/downloads/v1/dataPackages/{dataPackage}/versions/{version}")
    String osHttpURLTemplate,
    @DefaultValue("1073741824") long maxExtractedBytes,
    @DefaultValue("20") int sweepLimit,
    @DefaultValue("20s") Duration connectTimeout,
    @DefaultValue("840s") Duration requestTimeout) {
  public AcquisitionProperties {
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

  public static String required(String value, String name) {
    check(value != null && !value.isBlank(), "Missing " + name);
    return value;
  }
}
