package uk.gov.companieshouse.addresslookup.lambda.discovery;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import com.amazonaws.services.lambda.runtime.Context;
import com.amazonaws.services.lambda.runtime.events.APIGatewayV2HTTPEvent;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.MockedStatic;
import org.mockito.Mockito;
import org.mockito.junit.jupiter.MockitoExtension;
import uk.gov.companieshouse.addresslookup.lambda.discovery.service.DiscoveryService;
import uk.gov.companieshouse.addresslookup.lambda.shared.runtime.AcquisitionApplication;

@ExtendWith(MockitoExtension.class)
public class APIGatewayHTTPHandlerTest {

    @Mock
    private DiscoveryService discoveryService;

    @Mock
    private Context context;

    private APIGatewayHTTPHandler apiGatewayHTTPHandler;

    @BeforeEach
    public void setup() {
        try (MockedStatic<AcquisitionApplication> mocked =
                     Mockito.mockStatic(AcquisitionApplication.class)) {
            mocked.when(() -> AcquisitionApplication.start(DiscoveryService.class)).thenReturn(discoveryService);
            apiGatewayHTTPHandler = new APIGatewayHTTPHandler();
        }
    }
    private static final String TEST_EVENT_PAYLOAD = """
{
  "mode": "FULL",
  "versions": {
    "data_package": "20781",
    "version_id": "145051"
  }
}
""";
    private static final String TEST_RESPONSE_ID = "12345-67890-12345-67890";

    @Test
    public void shouldReturnReleaseIdForSuccessfulDiscovery() throws Exception {
        String payload = TEST_EVENT_PAYLOAD;
        Mockito.when(discoveryService.discover(payload)).thenReturn(TEST_RESPONSE_ID);

        APIGatewayV2HTTPEvent testEvent = APIGatewayV2HTTPEvent.builder()
                .withBody(payload)
                .build();

        String response = apiGatewayHTTPHandler.handleRequest(testEvent, context);

        assertEquals(TEST_RESPONSE_ID, response);
    }

    @Test
    public void shouldRuntimeExceptionForException() throws Exception {
        String payload = TEST_EVENT_PAYLOAD;
        String EXCEPTION_TEXT = "Something went wrong!!";

        Mockito.when(discoveryService.discover(payload)).thenThrow(new Exception(EXCEPTION_TEXT));

        APIGatewayV2HTTPEvent testEvent = APIGatewayV2HTTPEvent.builder()
                .withBody(payload)
                .build();

        String response = null;

        try {
            response = apiGatewayHTTPHandler.handleRequest(testEvent, context);
        } catch (Exception e) {
            assertInstanceOf(RuntimeException.class, e);
            System.err.println("Exception: " + e);
            assertTrue(e.getMessage().startsWith("discovery failed; durable state allows retry, event"));
            assertTrue(e.getCause().getMessage().contains(EXCEPTION_TEXT));
        }

        assertNull(response);
    }
}
