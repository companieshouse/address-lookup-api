package uk.gov.companieshouse.addresslookup.lambda.download;


import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import com.amazonaws.services.lambda.runtime.Context;
import com.amazonaws.services.lambda.runtime.events.SQSBatchResponse;
import com.amazonaws.services.lambda.runtime.events.SQSEvent;
import com.fasterxml.jackson.databind.JsonNode;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.MockedStatic;
import org.mockito.Mockito;
import org.mockito.junit.jupiter.MockitoExtension;
import uk.gov.companieshouse.addresslookup.lambda.download.service.DownloadService;
import uk.gov.companieshouse.addresslookup.lambda.shared.exception.TransientFailureException;
import uk.gov.companieshouse.addresslookup.lambda.shared.runtime.AcquisitionApplication;
import uk.gov.companieshouse.addresslookup.lambda.shared.runtime.RuntimeSupport;
import uk.gov.companieshouse.addresslookup.lambda.test.SQSMessageBuilder;

import java.util.List;
import java.util.Map;

@ExtendWith(MockitoExtension.class)
class SQSHandlerTest {

    @Mock
    private DownloadService service;

    @Mock
    private Context context;

    private SQSHandler handler;
    private static final String TEST_BODY_1 = """
            {
              "version": "0",
              "id": "2bf949e0-06d4-7223-b89d-54392b711694",
              "detail-type": "DOWNLOAD",
              "source": "os.acquisition",
              "account": "450865997890",
              "time": "2026-11-06T16:44:55Z",
              "region": "eu-west-2",
              "resources": [],
              "detail": {
                "dataset":"add_gb_royalmailaddress",
                "planKey": "acquisitions/2026-10-06/12345678-1234-1234-1234-123456787abc.json"
              }
            }
            """;
    private static final String TEST_BODY_2 = """
           {
              "version": "0",
              "id": "2bf949e0-06d4-7223-b89d-54392b711695",
              "detail-type": "DOWNLOAD",
              "source": "os.acquisition",
              "account": "450865997890",
              "time": "2026-11-06T16:44:55Z",
              "region": "eu-west-2",
              "resources": [],
              "detail": {
                "dataset":"add_isl_royalmailaddress",
                "planKey": "acquisitions/2026-10-06/12345678-1234-1234-1234-123456788abc.json"
              }
            }
            """;
    private static final String TEST_BODY_3 = """
           {
              "version": "0",
              "id": "2bf949e0-06d4-7223-b89d-54392b711696",
              "detail-type": "DOWNLOAD",
              "source": "os.acquisition",
              "account": "450865997890",
              "time": "2026-11-06T16:44:55Z",
              "region": "eu-west-2",
              "resources": [],
              "detail": {
                "dataset":"add_some_other_mailaddress",
                "planKey": "acquisitions/2026-10-06/12345678-1234-1234-1234-123456789abc.json"
              }
            }
            """;
    private static final SQSEvent.SQSMessage TEST_MESSAGE_1 = SQSMessageBuilder.builder()
            .messageId("1001")
            .body(TEST_BODY_1)
            .build();
    private static final SQSEvent.SQSMessage TEST_MESSAGE_2 = SQSMessageBuilder.builder()
            .messageId("1002")
            .body(TEST_BODY_2)
            .build();
    private static final SQSEvent.SQSMessage TEST_MESSAGE_3 = SQSMessageBuilder.builder()
            .messageId("1003")
            .body(TEST_BODY_3)
            .build();

    private Map<String, Object> TEST_MESSAGE_MAP_1 = Map.of("version", "0",
            "id", "2bf949e0-06d4-7223-b89d-54392b711694",
            "detail-type", "DOWNLOAD",
            "source", "os.acquisition",
            "account", "450865997890",
            "time", "2026-11-06T16:44:55Z",
            "region", "eu-west-2",
            "resources", List.of(),
            "detail", Map.of("dataset", "add_gb_royalmailaddress",
            "planKey", "acquisitions/2026-10-06/12345678-1234-1234-1234-123456787abc.json")
    );
    private Map<String, Object> TEST_MESSAGE_MAP_2 = Map.of("version", "0",
            "id", "2bf949e0-06d4-7223-b89d-54392b711695",
            "detail-type", "DOWNLOAD",
            "source", "os.acquisition",
            "account", "450865997890",
            "time", "2026-11-06T16:44:55Z",
            "region", "eu-west-2",
            "resources", List.of(),
            "detail", Map.of("dataset", "add_isl_royalmailaddress",
                    "planKey", "acquisitions/2026-10-06/12345678-1234-1234-1234-123456788abc.json")
    );
    private Map<String, Object> TEST_MESSAGE_MAP_3 = Map.of("version", "0",
            "id", "2bf949e0-06d4-7223-b89d-54392b711696",
            "detail-type", "DOWNLOAD",
            "source", "os.acquisition",
            "account", "450865997890",
            "time", "2026-11-06T16:44:55Z",
            "region", "eu-west-2",
            "resources", List.of(),
            "detail", Map.of("dataset", "add_some_other_mailaddress",
                    "planKey", "acquisitions/2026-10-06/12345678-1234-1234-1234-123456789abc.json")
    );
    private static final SQSEvent TEST_EVENT = new SQSEvent();

    static {
        TEST_EVENT.setRecords(List.of(TEST_MESSAGE_1, TEST_MESSAGE_2, TEST_MESSAGE_3));
    }

    private static final String TEST_RESPONSE_1 = "ZIP_SCAN";
    private static final String TEST_RESPONSE_2 = "CHECKSUM_MISMATCH";
    private static final String TEST_RESPONSE_3 = "SKIPPED";

    @BeforeEach
    void setup() {
        try (MockedStatic<AcquisitionApplication> mockApp = Mockito.mockStatic(AcquisitionApplication.class)) {
            mockApp.when(() -> AcquisitionApplication.start(DownloadService.class))
                    .thenReturn(service);
            handler = new SQSHandler();
        }
    }

    @Test
    void shouldReturnResponseForSuccessfulDownloadRun() throws Exception {

        Mockito.when(service.download(TEST_MESSAGE_MAP_1, context))
                .thenReturn(TEST_RESPONSE_1);
        Mockito.when(service.download(TEST_MESSAGE_MAP_2, context))
                .thenReturn(TEST_RESPONSE_2);
        Mockito.when(service.download(TEST_MESSAGE_MAP_3, context))
                .thenReturn(TEST_RESPONSE_3);

        SQSBatchResponse response = handler.handleRequest(TEST_EVENT, context);

        assertNotNull(response);
        assertEquals(2, response.getBatchItemFailures().size());
        assertEquals("1002",
                response.getBatchItemFailures().get(0).getItemIdentifier());
        assertEquals("1003",
                response.getBatchItemFailures().get(1).getItemIdentifier());
    }

    @Test
    void shouldThrowExceptionForException() throws Exception {

        final String EXCEPTION_TEXT = "Something went wrong!!";

        Mockito.when(service.download(TEST_MESSAGE_MAP_1, context))
                .thenReturn(TEST_RESPONSE_1);
        Mockito.when(service.download(TEST_MESSAGE_MAP_2, context))
                .thenThrow(new TransientFailureException(EXCEPTION_TEXT));

        SQSBatchResponse response = null;

        try {
            response = handler.handleRequest(TEST_EVENT, context);
        } catch (Exception e) {
            assertInstanceOf(RuntimeException.class, e);
            assertEquals("download failed; durable state allows retry", e.getMessage());
            assertTrue(e.getCause().getMessage().contains(EXCEPTION_TEXT));
        }

        assertNull(response);
    }

    @Test
    void shouldParseMessageBody() throws Exception {

        JsonNode bodyNode = RuntimeSupport.JSON.readTree(TEST_BODY_1);
        Map<?, ?> body = RuntimeSupport.JSON.convertValue(bodyNode, Map.class);
        assertNotNull(body);

        System.out.println("Body: " + body);

        var details = body.get("detail");
        JsonNode detailNode = bodyNode.get("detail");

        assertNotNull(details);
        assertNotNull(detailNode);

        assertInstanceOf(Map.class, details);

        System.out.println("Details: " + details);
        System.out.println("Detail Node: " + detailNode);
    }

}
