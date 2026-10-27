package uk.gov.companieshouse.addresslookup.lambda.scan;

import org.junit.jupiter.api.Test;
import uk.gov.companieshouse.addresslookup.lambda.shared.acquisition.AcquisitionProperties;

import java.time.Duration;

import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;

class ScanConfigurationTest {
  @Test
  void constructorRequiresIngestBucket() {
    assertThrows(
        IllegalArgumentException.class,
        () -> new ScanResultsService(null, null, "clean-bucket"));
  }

  @Test
  void constructorRequiresCleanBucket() {
    assertThrows(
        IllegalArgumentException.class,
        () -> new ScanResultsService(null, "ingest-bucket", null));
  }

  @Test
  void createsScanServiceWithoutSourceBucket() {
    var properties = new AcquisitionProperties(
        null, "clean-bucket", "ingest-bucket", "event-bus", null, null,
        1024, 1024, 20, Duration.ofSeconds(20), Duration.ofSeconds(840));

    assertNotNull(new ScanConfiguration().scanResultsService(null, properties));
  }
}
