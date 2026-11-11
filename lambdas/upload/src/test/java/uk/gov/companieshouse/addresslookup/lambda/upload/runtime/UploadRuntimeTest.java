package uk.gov.companieshouse.addresslookup.lambda.upload.runtime;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mockConstruction;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.boot.WebApplicationType;
import org.springframework.boot.builder.SpringApplicationBuilder;
import org.springframework.context.ConfigurableApplicationContext;
import uk.gov.companieshouse.addresslookup.lambda.upload.service.UploadService;

@ExtendWith(MockitoExtension.class)
class UploadRuntimeTest {
  @Mock private UploadService service;

  @Mock private ConfigurableApplicationContext context;

  @Test
  void propertiesRequireBothBuckets() {
    var properties = new UploadProperties("s", "i");
    assertEquals("s", properties.scannedBucket());
    assertEquals("i", properties.ingestBucket());
    assertThrows(IllegalArgumentException.class, () -> new UploadProperties(null, "i"));
    assertThrows(IllegalArgumentException.class, () -> new UploadProperties("s", " "));
  }

  @Test
  void databasePropertiesRequireHostNameAndUser() {
    assertThrows(IllegalArgumentException.class, () -> new DatabaseProperties("h", 0, "n", "u"));
    assertThrows(IllegalArgumentException.class, () -> new DatabaseProperties("", 5432, "n", "u"));
    assertThrows(
        IllegalArgumentException.class, () -> new DatabaseProperties("h", 5432, null, "u"));
    assertThrows(IllegalArgumentException.class, () -> new DatabaseProperties("h", 5432, "n", " "));
  }

  @Test
  void startReturnsServiceBeanFromNonWebContext() {
    when(context.getBean(UploadService.class)).thenReturn(service);

    try (var builders =
        mockConstruction(
            SpringApplicationBuilder.class,
            (builder, ctx) -> {
              when(builder.web(any(WebApplicationType.class))).thenReturn(builder);
              when(builder.run()).thenReturn(context);
            })) {
      assertSame(service, UploadApplication.start());
      verify(builders.constructed().getFirst()).web(WebApplicationType.NONE);
    }
  }
}
