package uk.gov.companieshouse.addresslookup.lambda.download;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.when;

import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.LoggerContext;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;

import com.amazonaws.services.lambda.runtime.Context;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;
import org.mockito.Mock;
import org.mockito.MockedStatic;
import org.mockito.Mockito;
import org.mockito.junit.jupiter.MockitoExtension;
import org.slf4j.LoggerFactory;

import software.amazon.awssdk.services.s3.model.S3Exception;

import uk.gov.companieshouse.addresslookup.lambda.download.service.DiscoveryService;
import uk.gov.companieshouse.addresslookup.lambda.download.service.DownloadService;
import uk.gov.companieshouse.addresslookup.lambda.shared.exception.AbstractLambdaFailureException;
import uk.gov.companieshouse.addresslookup.lambda.shared.exception.LambdaFailureRuntimeException;
import uk.gov.companieshouse.addresslookup.lambda.shared.exception.PermanentFailureException;
import uk.gov.companieshouse.addresslookup.lambda.shared.exception.TransientFailureException;
import uk.gov.companieshouse.addresslookup.lambda.shared.runtime.AcquisitionApplication;
import uk.gov.companieshouse.addresslookup.lambda.test.utils.TestUtils;
import uk.gov.companieshouse.logging.StructuredLogger;
import uk.gov.companieshouse.release.model.Dataset;

import java.util.Map;
import java.util.stream.Stream;

@ExtendWith(MockitoExtension.class)
class HandlerTest {

    private static final Logger STRUCTURED_LOGGER =
            (Logger) LoggerFactory.getLogger(StructuredLogger.class);
    private static final String RELEASE_ID = "4b845f1a-b0dc-33f1-b76b-b76bb7cd0c56";
    private static final String DATASET_NAME = "add_gb_royalmailaddress";

    @Mock
    private DownloadService downloadService;

    @Mock
    private DiscoveryService discoveryService;

    @Mock
    private Context context;

    private Handler handler;

    private static final Map<String, Object> TEST_FULL_UPDATE_CURSOR_JSON =
            Map.of("dataPackageId", "20780",
            "lastProcessedVersionId", "130989",
            "lastProcessedDate", "2026-05-01");

    private static final String TEST_RESPONSE = "ZIP_SCAN";

    private static String TEST_MANIFEST_JSON;
    @BeforeEach
    void setup() throws Exception {
        TEST_MANIFEST_JSON = TestUtils.readFileFromClasspath("output/output-manifest-Full.json");

        try (MockedStatic<AcquisitionApplication> mockApp =
                Mockito.mockStatic(AcquisitionApplication.class)) {
            mockApp.when(() -> AcquisitionApplication.start(DownloadService.class))
                    .thenReturn(downloadService);
            mockApp.when(() -> AcquisitionApplication.start(DiscoveryService.class))
                    .thenReturn(discoveryService);
            handler = new Handler();
        }
    }

    @Test
    void shouldReturnResponseForSuccessfulDownload() throws Exception {
        //final String TEST_MANIFEST_JSON = TestUtils.readFileFromClasspath("output/output-manifest-Full.json");
        Map<String, Object> downloadRequest = Map.of("manifest", TEST_MANIFEST_JSON);

        when(discoveryService.discover(TEST_FULL_UPDATE_CURSOR_JSON)).thenReturn(TEST_MANIFEST_JSON);
        when(downloadService.download(downloadRequest, context)).thenReturn(TEST_RESPONSE);

        String response = handler.handleRequest(TEST_FULL_UPDATE_CURSOR_JSON, context);

        assertEquals(TEST_RESPONSE, response);
    }

    @Test
    void shouldThrowExceptionForTransientFailureException() throws Exception {

        final String EXCEPTION_TEXT = "Something went wrong!!";
        Map<String, Object> downloadRequest = Map.of("manifest", TEST_MANIFEST_JSON);

        when(discoveryService.discover(TEST_FULL_UPDATE_CURSOR_JSON)).thenReturn(TEST_MANIFEST_JSON);
        when(downloadService.download(downloadRequest, context))
                .thenThrow(TransientFailureException.builder().description(EXCEPTION_TEXT).build());

        Exception exception =
                assertThrows(
                        LambdaFailureRuntimeException.class,
                        () -> handler.handleRequest(TEST_FULL_UPDATE_CURSOR_JSON, context));

        assertEquals("download failed; durable state allows retry", exception.getMessage());
        assertTrue(exception.getCause().getMessage().contains(EXCEPTION_TEXT));
    }

    @Test
    void shouldThrowExceptionForPermanentFailureException() throws Exception {
        final String EXCEPTION_TEXT = "Something went wrong!!";
        doTestForExceptionFailingInvocation(
                PermanentFailureException.builder().description(EXCEPTION_TEXT).build());
    }

    @Test
    void shouldThrowExceptionForNPE() throws Exception {
        doTestForExceptionFailingInvocation(new NullPointerException());
    }

    @Test
    void shouldThrowExceptionForS3404Exception() throws Exception {

        S3Exception.Builder exceptionBuilder = S3Exception.builder().statusCode(404);
        doTestForExceptionFailingInvocation(exceptionBuilder.build());
    }

    private void doTestForExceptionFailingInvocation(Exception exception) throws Exception {
        Map<String, Object> downloadRequest = Map.of("manifest", TEST_MANIFEST_JSON);
        when(discoveryService.discover(TEST_FULL_UPDATE_CURSOR_JSON)).thenReturn(TEST_MANIFEST_JSON);
        when(downloadService.download(downloadRequest, context)).thenThrow(exception);

        Exception failure =
                assertThrows(
                        LambdaFailureRuntimeException.class,
                        () -> handler.handleRequest(TEST_FULL_UPDATE_CURSOR_JSON, context));

        assertEquals("Download failed and a retry will not succeed", failure.getMessage());
        assertSame(exception, failure.getCause());
    }

    private static Stream<Arguments> failuresAndExpectedOutcomes() {
        return Stream.of(
                Arguments.of(
                        TransientFailureException.builder()
                                .description("Failed to check if manifest already stored.")
                                .dataset(Dataset.named(DATASET_NAME))
                                .reasonCode("FAILED_TO_STORE_MANIFEST")
                                .releaseId(RELEASE_ID)
                                .build(),
                        "TRANSIENT_FAILURE"),
                Arguments.of(
                        PermanentFailureException.builder()
                                .description("MD5 not available")
                                .dataset(Dataset.named(DATASET_NAME))
                                .reasonCode("UNRECOGNISED_DIGEST_SCHEME")
                                .releaseId(RELEASE_ID)
                                .build(),
                        "PERMANENT_FAILURE"));
    }

    @ParameterizedTest
    @MethodSource("failuresAndExpectedOutcomes")
    void failureLogHasOutcomeReasonCodeReleaseIdAndDataset(
            AbstractLambdaFailureException failure, String expectedOutcome) throws Exception {
        Map<String, Object> downloadRequest = Map.of("manifest", TEST_MANIFEST_JSON);
        when(discoveryService.discover(TEST_FULL_UPDATE_CURSOR_JSON)).thenReturn(TEST_MANIFEST_JSON);
        when(downloadService.download(downloadRequest, context)).thenThrow(failure);
        var appender = captureStructuredLogs();

        try {
            assertThrows(
                    LambdaFailureRuntimeException.class,
                    () -> handler.handleRequest(TEST_FULL_UPDATE_CURSOR_JSON, context));
        } finally {
            stopCapturingStructuredLogs(appender);
        }
        ILoggingEvent failureLog = appender.list.getFirst();
        assertEquals(expectedOutcome, structuredField(failureLog, "outcome"));
        assertEquals(failure.reasonCode(), structuredField(failureLog, "reason_code"));
        assertEquals(RELEASE_ID, structuredField(failureLog, "release_id"));
        assertEquals(DATASET_NAME, structuredField(failureLog, "dataset"));
    }

    @Test
    void failureLogLeavesOutAbsentReleaseIdAndDataset() throws Exception {
        TransientFailureException failure =
                TransientFailureException.builder()
                        .description("Failed to read manifest")
                        .reasonCode("FAILED_TO_READ_MANIFEST")
                        .build();
        Map<String, Object> downloadRequest = Map.of("manifest", TEST_MANIFEST_JSON);
        when(discoveryService.discover(TEST_FULL_UPDATE_CURSOR_JSON)).thenReturn(TEST_MANIFEST_JSON);
        when(downloadService.download(downloadRequest, context)).thenThrow(failure);
        var appender = captureStructuredLogs();

        try {
            assertThrows(
                    LambdaFailureRuntimeException.class,
                    () -> handler.handleRequest(TEST_FULL_UPDATE_CURSOR_JSON, context));
        } finally {
            stopCapturingStructuredLogs(appender);
        }

        ILoggingEvent failureLog = appender.list.getFirst();
        assertEquals("FAILED_TO_READ_MANIFEST", structuredField(failureLog, "reason_code"));
        assertNull(structuredField(failureLog, "release_id"));
        assertNull(structuredField(failureLog, "dataset"));
    }

    private static ListAppender<ILoggingEvent> captureStructuredLogs() {
        var appender = new ListAppender<ILoggingEvent>();
        appender.setContext((LoggerContext) LoggerFactory.getILoggerFactory());
        appender.start();
        STRUCTURED_LOGGER.addAppender(appender);
        return appender;
    }

    private static void stopCapturingStructuredLogs(ListAppender<ILoggingEvent> appender) {
        STRUCTURED_LOGGER.detachAppender(appender);
        appender.stop();
    }

    private static String structuredField(ILoggingEvent event, String field) {
        for (Object argument : event.getArgumentArray()) {
            if (argument instanceof Map<?, ?> logData
                    && logData.get("data") instanceof Map<?, ?> data
                    && data.get(field) != null) {
                return data.get(field).toString();
            }
        }
        return null;
    }
}
