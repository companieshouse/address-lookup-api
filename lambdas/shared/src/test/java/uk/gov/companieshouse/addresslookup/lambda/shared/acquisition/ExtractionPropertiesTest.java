package uk.gov.companieshouse.addresslookup.lambda.shared.acquisition;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;

import java.io.IOException;
import java.util.HashMap;
import java.util.List;
import java.util.Properties;
import org.junit.jupiter.api.Test;
import org.springframework.boot.context.properties.bind.Bindable;
import org.springframework.boot.context.properties.bind.Binder;
import org.springframework.boot.context.properties.source.MapConfigurationPropertySource;

final class ExtractionPropertiesTest {
  @Test
  void bindsDistinctSuffixesAndLimitsForEveryFullDataset() throws IOException {
    var properties = new Properties();
    try (var stream = getClass().getResourceAsStream("/acquisition.properties")) {
      assertNotNull(stream);
      properties.load(stream);
    }

    var values = new HashMap<String, String>();
    for (String name : properties.stringPropertyNames()) {
      values.put(name, properties.getProperty(name));
    }
    List<ExtractionProperties.DatasetPolicy> policies =
        new Binder(new MapConfigurationPropertySource(values))
            .bind("extraction.full.dataset-policies",
                Bindable.listOf(ExtractionProperties.DatasetPolicy.class))
            .orElseThrow(() -> new IllegalStateException("Missing FULL extraction policies"));

    assertEquals(6, policies.size());
    assertEquals(6, policies.stream().map(ExtractionProperties.DatasetPolicy::dataset)
        .distinct().count());
    assertEquals(List.of("_altadd", "_otrclass"), policies.get(3).extractOnly());
    assertEquals(List.of("_rltenty"), policies.get(3).skip());
    assertEquals(68719476736L, policies.get(1).maxExtractedBytes());
    for (var policy : policies) {
      assertEquals(99, policy.maxEntries());
      assertEquals(100, policy.maxEntryCompressionRatio());
      assertEquals(20, policy.maxArchiveCompressionRatio());
    }
  }
}
