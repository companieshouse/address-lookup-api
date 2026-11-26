package uk.gov.companieshouse.addresslookup.lambda.extraction.service;

import java.util.List;
import java.util.Objects;

public record ExtractionResult(Status status, String mode, String version,
                               Integer filesDone, Integer filesRemaining, List<Failure> failed) {
  public enum Status {
    IN_PROGRESS, COMPLETE, NOTHING_NEW, NEEDS_ATTENTION
  }

  public record Failure(String key, String code) {}

  public ExtractionResult {
    Objects.requireNonNull(status);
    Objects.requireNonNull(mode);
    Objects.requireNonNull(version);
    failed = List.copyOf(failed);
    if ((filesDone == null) != (filesRemaining == null)
        || (filesDone != null && (filesDone < 0 || filesRemaining < 0))) {
      throw new IllegalArgumentException("Invalid extraction file counts");
    }
  }
}
