package uk.gov.companieshouse.addresslookup.lambda.scan;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.fail;
import com.amazonaws.services.lambda.runtime.Context;
import com.fasterxml.jackson.core.JsonProcessingException;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.MockedStatic;
import org.mockito.Mockito;
import org.mockito.junit.jupiter.MockitoExtension;
import uk.gov.companieshouse.addresslookup.lambda.scan.service.ScanResultsService;
import uk.gov.companieshouse.addresslookup.lambda.shared.runtime.AcquisitionApplication;
import uk.gov.companieshouse.addresslookup.lambda.shared.runtime.RuntimeSupport;

import java.util.Map;

@ExtendWith(MockitoExtension.class)
class HandlerTest {

    @Mock
    private ScanResultsService service;

    @Mock
    private Context context;

    private Handler handler;

    private static final Map<String, Object> TEST_EVENT;

    static {
        Map<String, Object> event = null;
        try {
             event = RuntimeSupport.JSON.readValue("""
                    {
                      "detail":
                      {
                        "s3ObjectDetails":
                        {
                          "bucketName":"ingest-bucket",
                          "versionId":"3HL4kqtJlcpXroDTDmJ.rmSpXd3dIbrHY",
                          "objectKey":"acquisitions/2026-10-15/f3d8c9a7-7b4e-4f62-a9d1-8c5e72f1b6a4.json"
                        },
                        "scanResultDetails":
                          {
                            "scanResultStatus":"NO_THREATS_FOUND"
                          },
                        "scanStatus":"COMPLETED"
                      },
                      "id":"evt-1",
                      "source":"aws.guardduty",
                      "detail-type":"GuardDuty Malware Protection Object Scan Result"
                    }
                    """, Map.class);
        } catch (JsonProcessingException e) {
            fail(e);
        }
        TEST_EVENT = event;

    }
    private static final String TEST_RESPONSE = "ExtractRequested";

    @BeforeEach
    void setup() {
        try (MockedStatic<AcquisitionApplication> mockApp = Mockito.mockStatic(AcquisitionApplication.class)) {
            mockApp.when(() -> AcquisitionApplication.start(ScanResultsService.class))
                    .thenReturn(service);
            handler = new Handler();
        }
    }

    @Test
    void shouldReturnResponseForSuccessfulDownload() throws Exception {

        Mockito.when(service.scan(TEST_EVENT))
                .thenReturn(TEST_RESPONSE);

        String response = handler.handleRequest(TEST_EVENT, context);

        assertEquals(TEST_RESPONSE, response);
    }

    @Test
    void shouldThrowExceptionForException() throws Exception {

        final String EXCEPTION_TEXT = "Something went wrong!!";

        Mockito.when(service.scan(TEST_EVENT))
                .thenThrow(new Exception(EXCEPTION_TEXT));

        String response = null;

        try {
            response = handler.handleRequest(TEST_EVENT, context);
        } catch (Exception e) {
            assertInstanceOf(RuntimeException.class, e);
            assertEquals("scan failed; PutEvents failures are retried", e.getMessage());
            assertTrue(e.getCause().getMessage().contains(EXCEPTION_TEXT));
        }

        assertNull(response);
    }
}
