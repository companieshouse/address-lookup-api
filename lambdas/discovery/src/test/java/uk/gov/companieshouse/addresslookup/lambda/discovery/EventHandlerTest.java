package uk.gov.companieshouse.addresslookup.lambda.discovery;


import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import com.amazonaws.services.lambda.runtime.Context;
import com.amazonaws.services.lambda.runtime.events.ScheduledEvent;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.MockedStatic;
import org.mockito.Mockito;
import org.mockito.junit.jupiter.MockitoExtension;
import uk.gov.companieshouse.addresslookup.lambda.discovery.service.DiscoveryService;
import uk.gov.companieshouse.addresslookup.lambda.shared.runtime.AcquisitionApplication;

import java.util.Map;

@ExtendWith(MockitoExtension.class)
public class EventHandlerTest {

    @Mock
    private DiscoveryService discoveryService;

    @Mock
    private Context context;

    private EventHandler eventHandler;

    @BeforeEach
    public void setup() {
        try (MockedStatic<AcquisitionApplication> mocked =
                     Mockito.mockStatic(AcquisitionApplication.class)) {
            mocked.when(() -> AcquisitionApplication.start(DiscoveryService.class)).thenReturn(discoveryService);
            eventHandler = new EventHandler();
        }
    }
    private static final Map<String, Object> TEST_DETAILS = Map.of("mode", "COU");
    private static final String TEST_RESPONSE_ID = "12345-67890-12345-67890";

    @Test
    public void shouldReturnReleaseIdForSuccessfulDiscovery() throws Exception {

        Mockito.when(discoveryService.discover(TEST_DETAILS))
                .thenReturn(TEST_RESPONSE_ID);

        ScheduledEvent testEvent = new ScheduledEvent();
        testEvent.setDetail(TEST_DETAILS);

        String response = eventHandler.handleRequest(testEvent, context);

        assertEquals(TEST_RESPONSE_ID, response);
    }

    @Test
    public void shouldReturnNoCommonContiguousReleaseResponse() throws Exception {
        String expectedResponse = "NO_COMMON_CONTIGUOUS_RELEASE";

        Mockito.when(discoveryService.discover(TEST_DETAILS))
                .thenReturn(expectedResponse);

        ScheduledEvent testEvent = new ScheduledEvent();
        testEvent.setDetail(TEST_DETAILS);

        String response = eventHandler.handleRequest(testEvent, context);

        assertEquals(expectedResponse, response);
    }

    @Test
    public void shouldRuntimeExceptionForException() throws Exception {

        String EXCEPTION_TEXT = "Something went wrong!!";

        Mockito.when(discoveryService.discover(TEST_DETAILS))
                .thenThrow(new Exception(EXCEPTION_TEXT));

        ScheduledEvent testEvent = new ScheduledEvent();
        testEvent.setDetail(TEST_DETAILS);

        String response = null;

        try {
            response = eventHandler.handleRequest(testEvent, context);
        } catch (Exception e) {
            assertInstanceOf(RuntimeException.class, e);
            System.err.println("Exception: " + e);
            assertTrue(e.getMessage().startsWith("discovery failed; durable state allows retry, event"));
            assertTrue(e.getCause().getMessage().contains(EXCEPTION_TEXT));
        }

        assertNull(response);
    }
}
