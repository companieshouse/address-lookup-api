package uk.gov.companieshouse.addresslookup.lambda.shared;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;
import com.amazonaws.services.lambda.runtime.Context;
import com.fasterxml.jackson.core.JsonProcessingException;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import software.amazon.awssdk.awscore.exception.AwsServiceException;
import uk.gov.companieshouse.addresslookup.lambda.shared.acquisition.Commands;
import uk.gov.companieshouse.addresslookup.lambda.shared.exception.PermanentFailureException;
import uk.gov.companieshouse.addresslookup.lambda.shared.exception.TransientFailureException;

import java.util.Map;

@ExtendWith(MockitoExtension.class)
class AbstractRequestHandlerImplTest {

    @Mock
    private TestService testService;

    @Mock
    private Context context;

    private AbstractRequestHandlerImpl<Map<String, Object>, String> testHandler;

    @BeforeEach
    void setUp() {
        testHandler = new TestRequestHandlerImpl(testService);
    }

    @Test
    void shouldReturnSuccessForNoExceptionsHappyPath() throws Exception {
        when(testService.run()).thenReturn("SUCCESS");

        String response = testHandler.handleRequest(Map.of(), context);

        assertEquals("SUCCESS", response);
    }

    @Test
    void shouldReturnFailureForPermanentFailureException() throws Exception {
        when(testService.run()).thenThrow(PermanentFailureException.builder().description("Something went wrong that can't be fixed with a retry").build());
        String response = testHandler.handleRequest(Map.of(), context);

        assertEquals("FAILURE", response);
    }

    @Test
    void shouldThrowRuntimeExceptionForTransientFailureException() throws Exception {
        when(testService.run()).thenThrow(TransientFailureException.builder().description("Something went wrong but might be OK with a retry").build());
        Map<String, Object> request = Map.of();
        assertThrows(RuntimeException.class, () -> {
            testHandler.handleRequest(request, context);
        });
    }

    @Test
    void shouldReturnFailureForCommandDispatchExceptionWithJSONParsingException() throws Exception {
        Commands.CommandDispatchException exception = mock(Commands.CommandDispatchException.class);
        when(exception.getCause()).thenReturn(mock(JsonProcessingException.class));
        when(testService.run()).thenThrow(exception);
        String response = testHandler.handleRequest(Map.of(), context);

        assertEquals("FAILURE", response);
    }

    @Test
    void shouldThrowAwsServiceExceptionForCommandDispatchExceptionNonJSONParsingException() throws Exception {
        Commands.CommandDispatchException exception = mock(Commands.CommandDispatchException.class);
        when(exception.getMessage()).thenReturn("Something went wrong sending the command, but might work next time");

        when(testService.run()).thenThrow(exception);

        Map<String, Object> request = Map.of();
        assertThrows(AwsServiceException.class, () -> {
            testHandler.handleRequest(request, context);
        });
    }

    @Test
    void shouldReturnFailureForIllegalArgumentException() throws Exception {
        IllegalArgumentException exception = new IllegalArgumentException("Parameter missing");
        when(testService.run()).thenThrow(exception);
        String response = testHandler.handleRequest(Map.of(), context);

        assertEquals("FAILURE", response);
    }

    @Test
    void shouldReturnFailureForAnyOtherRuntimeException() throws Exception {
        Exception exception = new RuntimeException("Something wrong that was not anticipated, so try again");

        when(testService.run()).thenThrow(exception);

        Map<String, Object> request = Map.of();
        assertEquals("FAILURE", testHandler.handleRequest(request, context));
    }

    private class TestRequestHandlerImpl extends
            AbstractRequestHandlerImpl<Map<String, Object>, String> {

        private TestService testService;

        TestRequestHandlerImpl(TestService testService) {
            this.testService = testService;
        }

        @Override
        protected String requestOperation(Map<String, Object> request, Context context)
                throws PermanentFailureException, TransientFailureException {
            return this.testService.run();
        }

        @Override
        protected String failureResponse() {
            return "FAILURE";
        }

        @Override
        protected void handlePermanentException(Exception permanentException) {
            System.err.println(permanentException.getMessage());
        }

        @Override
        protected void handleTransientException(Exception transientException) {
            throw  AwsServiceException.builder().cause(transientException).build();
        }
    }

    private interface TestService {
        String run() throws PermanentFailureException, TransientFailureException;
    }

}
