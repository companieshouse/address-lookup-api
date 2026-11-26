package uk.gov.companieshouse.addresslookup.lambda.extraction;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;

import com.amazonaws.services.lambda.runtime.Context;
import java.io.IOException;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.MockedStatic;
import org.mockito.Mockito;
import org.mockito.junit.jupiter.MockitoExtension;
import uk.gov.companieshouse.addresslookup.lambda.extraction.service.ExtractionComponent;
import uk.gov.companieshouse.addresslookup.lambda.extraction.service.ExtractionResult;
import uk.gov.companieshouse.addresslookup.lambda.shared.runtime.AcquisitionApplication;

@ExtendWith(MockitoExtension.class)
class HandlerTest {
  @Mock private ExtractionComponent service;
  @Mock private Context context;

  private Handler handler;

  private static final Map<String, Object> TEST_EVENT =
      Map.of("mode", "FULL", "versions", Map.of("data_package", "24833", "version_id", "150450"));
  private static final ExtractionResult TEST_RESPONSE =
      new ExtractionResult(ExtractionResult.Status.COMPLETE, "FULL", "150450", 2, 0, List.of());

  @BeforeEach
  void setup() {
    try (MockedStatic<AcquisitionApplication> mockApp =
        Mockito.mockStatic(AcquisitionApplication.class)) {
      mockApp.when(() -> AcquisitionApplication.start(ExtractionComponent.class)).thenReturn(service);
      handler = new Handler();
    }
  }

  @Test
  void returnsFullExtractionResult() throws Exception {
    Mockito.when(service.extract(TEST_EVENT, context)).thenReturn(TEST_RESPONSE);

    ExtractionResult response = handler.handleRequest(TEST_EVENT, context);

    assertEquals(TEST_RESPONSE, response);
  }

  @Test
  void failsInvocationForExtractionError() throws Exception {
    var failure = new IllegalArgumentException("Invalid FULL request");
    Mockito.when(service.extract(TEST_EVENT, context)).thenThrow(failure);

    var thrown =
        assertThrows(
            Handler.ExtractionFailureException.class,
            () -> handler.handleRequest(TEST_EVENT, context));
    assertSame(failure, thrown.getCause());
  }

  @Test
  void wrapsIOException() throws Exception {
    var failure = new IOException("Something went wrong!!");
    Mockito.when(service.extract(TEST_EVENT, context)).thenThrow(failure);

    var thrown =
        assertThrows(
            Handler.ExtractionFailureException.class,
            () -> handler.handleRequest(TEST_EVENT, context));
    assertEquals("extraction failed {Something went wrong!!}", thrown.getMessage());
    assertSame(failure, thrown.getCause());
  }
}
