package uk.gov.companieshouse.addresslookup.lambda.download;


import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import com.amazonaws.services.lambda.runtime.Context;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.MockedStatic;
import org.mockito.Mockito;
import org.mockito.junit.jupiter.MockitoExtension;
import uk.gov.companieshouse.addresslookup.lambda.shared.acquisition.DownloadService;
import uk.gov.companieshouse.addresslookup.lambda.shared.runtime.AcquisitionApplication;

import java.util.Map;

@ExtendWith(MockitoExtension.class)
class HandlerTest {

    @Mock
    private DownloadService service;

    @Mock
    private Context context;

    private Handler handler;

    private static final Map<String, Object> TEST_EVENT = Map.of("dataset", "add_gb_royalmailaddress",
            "planKey", "acquisitions/2026-10-06/12345678-1234-1234-1234-123456789abc.json");
    private static final String TEST_RESPONSE = "ZIP_SCAN";

    @BeforeEach
    void setup() {
        try (MockedStatic<AcquisitionApplication> mockApp = Mockito.mockStatic(AcquisitionApplication.class)) {
            mockApp.when(() -> AcquisitionApplication.start(DownloadService.class))
                    .thenReturn(service);
            handler = new Handler();
        }
    }

    @Test
    void shouldReturnResponseForSuccessfulDownload() throws Exception {

        Mockito.when(service.download(TEST_EVENT, context))
                .thenReturn(TEST_RESPONSE);

        String response = handler.handleRequest(TEST_EVENT, context);

        assertEquals(TEST_RESPONSE, response);
    }

    @Test
    void shouldThrowExceptionForException() throws Exception {

        final String EXCEPTION_TEXT = "Something went wrong!!";

        Mockito.when(service.download(TEST_EVENT, context))
                .thenThrow(new Exception(EXCEPTION_TEXT));

        String response = null;

        try {
            response = handler.handleRequest(TEST_EVENT, context);
        } catch (Exception e) {
            assertInstanceOf(RuntimeException.class, e);
            assertEquals("download failed; durable state allows retry", e.getMessage());
            assertTrue(e.getCause().getMessage().contains(EXCEPTION_TEXT));
        }

        assertNull(response);
    }
}
