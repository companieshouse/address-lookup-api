package uk.gov.companieshouse.addresslookup.lambda.shared.acquisition;

import static uk.gov.companieshouse.addresslookup.lambda.shared.runtime.RuntimeSupport.check;

import java.time.Duration;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;
import uk.gov.companieshouse.addresslookup.lambda.shared.util.KeyLayout.Feed;
import uk.gov.companieshouse.release.model.DatasetDefault;

@ConfigurationProperties("acquisition")
public record AcquisitionProperties(
    String controlBucket,
    String landingBucket,
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

  public Feed feedForPackage(String packageId) {
    return packageFeeds().get(packageId);
  }

  public List<String> packagesForFeed(Feed feed) {
    return packageFeeds().entrySet().stream()
        .filter(entry -> entry.getValue() == feed)
        .map(Map.Entry::getKey)
        .toList();
  }

  private Map<String, Feed> packageFeeds() {
    var feeds = new LinkedHashMap<String, Feed>();
    if (packages == null || packages.isBlank()) return feeds;
    for (String configured : packages.split(",")) {
      String[] parts = configured.trim().split("=", -1);
      check(parts.length == 2, "OS_PACKAGES entries must be packageId=feed");
      String packageId = parts[0].trim();
      check(packageId.matches("[A-Za-z0-9_-]+"), "Invalid OS package ID in OS_PACKAGES");
      Feed feed;
      try {
        feed = Feed.fromPrefix(parts[1].trim());
      } catch (IllegalArgumentException e) {
        throw new IllegalArgumentException("Invalid feed in OS_PACKAGES", e);
      }
      check(feeds.putIfAbsent(packageId, feed) == null, "Duplicate package ID in OS_PACKAGES");
    }
    return feeds;
  }
}
