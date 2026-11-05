package uk.gov.companieshouse.addresslookup.lambda.upload;

import static org.junit.jupiter.api.Assertions.assertSame;
import static org.mockito.Mockito.when;

import com.amazonaws.services.lambda.runtime.Context;
import com.amazonaws.services.lambda.runtime.events.SQSBatchResponse;
import com.amazonaws.services.lambda.runtime.events.SQSEvent;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.MockedStatic;
import org.mockito.Mockito;
import org.mockito.junit.jupiter.MockitoExtension;
import uk.gov.companieshouse.addresslookup.lambda.upload.runtime.UploadApplication;
import uk.gov.companieshouse.addresslookup.lambda.upload.service.UploadService;

@ExtendWith(MockitoExtension.class)
class HandlerTest {

  @Mock private UploadService service;

  @Mock private Context context;

  private Handler handler;

  @BeforeEach
  void setup() {
    try (MockedStatic<UploadApplication> app = Mockito.mockStatic(UploadApplication.class)) {
      app.when(UploadApplication::start).thenReturn(service);
      handler = new Handler();
    }
  }

  @Test
  void shouldReturnBatchResponseFromService() {
    var event = new SQSEvent();
    var expected = new SQSBatchResponse();
    when(service.upload(event, context)).thenReturn(expected);

    assertSame(expected, handler.handleRequest(event, context));
  }
}
