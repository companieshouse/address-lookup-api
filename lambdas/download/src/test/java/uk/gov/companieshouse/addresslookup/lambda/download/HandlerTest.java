package uk.gov.companieshouse.addresslookup.lambda.download;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.amazonaws.services.lambda.runtime.Context;
import java.util.Map;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.MockedStatic;
import org.mockito.Mockito;
import org.mockito.junit.jupiter.MockitoExtension;
import software.amazon.awssdk.services.s3.model.S3Exception;
import uk.gov.companieshouse.addresslookup.lambda.download.service.DownloadService;
import uk.gov.companieshouse.addresslookup.lambda.shared.exception.LambdaFailureRuntimeException;
import uk.gov.companieshouse.addresslookup.lambda.shared.exception.PermanentFailureException;
import uk.gov.companieshouse.addresslookup.lambda.shared.exception.TransientFailureException;
import uk.gov.companieshouse.addresslookup.lambda.shared.runtime.AcquisitionApplication;

@ExtendWith(MockitoExtension.class)
class HandlerTest {

  @Mock private DownloadService service;

  @Mock private Context context;

  private Handler handler;

  private static final Map<String, Object> TEST_EVENT =
      Map.of(
          "dataset",
          "add_gb_royalmailaddress",
          "planKey",
          "initial_full/12345/159159/manifest/data_package_manifest.json");
  private static final String TEST_RESPONSE = "ZIP_SCAN";

  @BeforeEach
  void setup() {
    try (MockedStatic<AcquisitionApplication> mockApp =
        Mockito.mockStatic(AcquisitionApplication.class)) {
      mockApp.when(() -> AcquisitionApplication.start(DownloadService.class)).thenReturn(service);
      handler = new Handler();
    }
  }

  @Test
  void shouldReturnResponseForSuccessfulDownload() throws Exception {

    Mockito.when(service.download(TEST_EVENT, context)).thenReturn(TEST_RESPONSE);

    String response = handler.handleRequest(TEST_EVENT, context);

    assertEquals(TEST_RESPONSE, response);
  }

  @Test
  void shouldThrowExceptionForTransientFailureException() throws Exception {

    final String EXCEPTION_TEXT = "Something went wrong!!";

    Mockito.when(service.download(TEST_EVENT, context))
        .thenThrow(TransientFailureException.builder().description(EXCEPTION_TEXT).build());

    Exception exception =
        assertThrows(
            LambdaFailureRuntimeException.class, () -> handler.handleRequest(TEST_EVENT, context));

    assertEquals("download failed; durable state allows retry", exception.getMessage());
    assertTrue(exception.getCause().getMessage().contains(EXCEPTION_TEXT));
  }

  @Test
  void shouldReturnFailureForPermanentFailureException() throws Exception {
    final String EXCEPTION_TEXT = "Something went wrong!!";
    doTestForExceptionReturningFailure(
        PermanentFailureException.builder().description(EXCEPTION_TEXT).build());
  }

  @Test
  void shouldReturnFailureForNPE() throws Exception {
    doTestForExceptionReturningFailure(new NullPointerException());
  }

  @Test
  void shouldReturnFailureForS3404Exception() throws Exception {

    S3Exception.Builder exceptionBuilder = S3Exception.builder().statusCode(404);
    doTestForExceptionReturningFailure(exceptionBuilder.build());
  }

  private void doTestForExceptionReturningFailure(Exception exception) throws Exception {
    Mockito.when(service.download(TEST_EVENT, context)).thenThrow(exception);

    String response = handler.handleRequest(TEST_EVENT, context);

    assertEquals("FAILURE", response);
  }
}
