package uk.gov.companieshouse.addresslookup.lambda.shared;

import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;
import com.amazonaws.services.lambda.runtime.Context;
import com.fasterxml.jackson.core.JsonProcessingException;
import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import software.amazon.awssdk.awscore.exception.AwsServiceException;
import uk.gov.companieshouse.addresslookup.lambda.shared.acquisition.Commands;
import uk.gov.companieshouse.addresslookup.lambda.shared.exception.PermanentFailureException;
import uk.gov.companieshouse.addresslookup.lambda.shared.exception.TransientFailureException;
import uk.gov.companieshouse.addresslookup.lambda.shared.storage.StreamingS3;

import java.util.Map;

@ExtendWith(MockitoExtension.class)
public class AbstractRequestHandlerImplTest {

    @Mock
    private TestService testService;

    @Mock
    private Context context;

    private AbstractRequestHandlerImpl<Map<String, Object>, String> testHandler =
            new TestRequestHandlerImpl();

    @Test
    void shouldReturnSuccessForNoExceptionsHappyPath() throws Exception {
        when(testService.run()).thenReturn("SUCCESS");

        String response = testHandler.handleRequest(Map.of(), context);

        Assertions.assertEquals("SUCCESS", response);
    }

    @Test
    void shouldReturnFailureForPermanentFailureException() throws Exception {
        when(testService.run()).thenThrow(new PermanentFailureException("Something went wrong that can't be fixed with a retry"));
        String response = testHandler.handleRequest(Map.of(), context);

        Assertions.assertEquals("FAILURE", response);
    }

    @Test
    void shouldThrowAwsServiceExceptionForTransientFailureException() throws Exception {
        when(testService.run()).thenThrow(new TransientFailureException("Something went wrong but might be OK with a retry"));
        Assertions.assertThrows(AwsServiceException.class, () -> {
            testHandler.handleRequest(Map.of(), context);
        });
    }

    @Test
    void shouldReturnFailureForCommandDispatchExceptionWithJSONParsingException() throws Exception {
        Commands.CommandDispatchException exception = mock(Commands.CommandDispatchException.class);
        when(exception.getCause()).thenReturn(mock(JsonProcessingException.class));
        when(testService.run()).thenThrow(exception);
        String response = testHandler.handleRequest(Map.of(), context);

        Assertions.assertEquals("FAILURE", response);
    }

    @Test
    void shouldThrowAwsServiceExceptionForCommandDispatchExceptionNonJSONParsingException() throws Exception {
        Commands.CommandDispatchException exception = mock(Commands.CommandDispatchException.class);
        when(exception.getMessage()).thenReturn("Something went wrong sending the command, but might work next time");

        when(testService.run()).thenThrow(exception);

        Assertions.assertThrows(AwsServiceException.class, () -> {
            testHandler.handleRequest(Map.of(), context);
        });
    }

    @Test
    void shouldForIllegalArgumentException() throws Exception {
        IllegalArgumentException exception = new IllegalArgumentException("Parameter missing");
        when(testService.run()).thenThrow(exception);
        String response = testHandler.handleRequest(Map.of(), context);

        Assertions.assertEquals("FAILURE", response);
    }

    @Test
    void shouldThrowAwsServiceExceptionForAnyOtherRuntimeException() throws Exception {
        Exception exception = new RuntimeException("Something wrong that was not anticipated, so try again");

        when(testService.run()).thenThrow(exception);

        Assertions.assertThrows(AwsServiceException.class, () -> {
            testHandler.handleRequest(Map.of(), context);
        });
    }

    private class TestRequestHandlerImpl extends
            AbstractRequestHandlerImpl<Map<String, Object>, String> {

        @Override
        protected String requestOperation(Map<String, Object> request, Context context)
                throws PermanentFailureException, TransientFailureException {
            return testService.run();
        }

        @Override
        protected String failureResponse() {
            return "FAILURE";
        }

        @Override
        protected void handlePermanentException(Throwable permanentException) {

        }

        @Override
        protected void handleTransientException(Throwable transientException) {
            throw  AwsServiceException.builder().cause(transientException).build();
        }
    }

    private interface TestService {
        String run() throws PermanentFailureException, TransientFailureException;
    }

}
