package uk.gov.companieshouse.addresslookup.lambda.shared.acquisition;

import java.util.HashSet;
import java.util.List;
import org.springframework.boot.context.properties.ConfigurationProperties;

@ConfigurationProperties("extraction")
public record ExtractionProperties(Feed full, Feed cou) {
  public record Feed(String packageId, String controlPrefix,
                     String landingPrefix, List<DatasetPolicy> datasetPolicies) {}

  public record DatasetPolicy(String dataset, List<String> extractOnly,
                              List<String> skip, long maxExtractedBytes, int maxEntries,
                              int maxEntryCompressionRatio, int maxArchiveCompressionRatio) {
    public DatasetPolicy {
      if (dataset == null || dataset.isBlank() || maxExtractedBytes <= 0
          || maxEntries <= 0 || maxEntries >= 100 || maxEntryCompressionRatio <= 0
          || maxArchiveCompressionRatio <= 0) {
        throw new IllegalArgumentException("Invalid extraction dataset or ZIP limits");
      }
      if (extractOnly == null || skip == null) {
        throw new IllegalArgumentException("Missing extraction entry policy for " + dataset);
      }
      extractOnly = List.copyOf(extractOnly);
      skip = List.copyOf(skip);
      var suffixes = new HashSet<>(extractOnly);
      if (suffixes.size() != extractOnly.size() || new HashSet<>(skip).size() != skip.size()
          || !suffixes.stream().allMatch(suffix -> suffix.matches("_[a-z0-9_]+"))
          || !skip.stream().allMatch(suffix -> suffix.matches("_[a-z0-9_]+"))
          || skip.stream().anyMatch(suffixes::contains)) {
        throw new IllegalArgumentException("Invalid extraction entry policy for " + dataset);
      }
    }
  }
}
