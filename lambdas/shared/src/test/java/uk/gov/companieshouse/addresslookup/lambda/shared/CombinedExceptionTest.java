package uk.gov.companieshouse.addresslookup.lambda.shared;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import uk.gov.companieshouse.addresslookup.lambda.shared.exception.PermanentFailureException;
import uk.gov.companieshouse.addresslookup.lambda.shared.exception.TransientFailureException;

import java.lang.reflect.Constructor;

class CombinedExceptionTest {

    @ParameterizedTest
    @ValueSource(classes = {PermanentFailureException.class, TransientFailureException.class})
    @SuppressWarnings("unchecked")
    void shouldCreateExceptionFromMessageOnlyConstructor(Class<? extends Exception>
                                                                 testExceptionClass) throws Exception {

        Constructor<? extends Exception> constructor =
                testExceptionClass.getConstructor(String.class);
        final String TEST_MESSAGE = "Test Message";
        Exception exception = constructor.newInstance(TEST_MESSAGE);

        assertNotNull(exception);
        assertEquals(TEST_MESSAGE, exception.getMessage());
    }


    @ParameterizedTest
    @ValueSource(classes = {PermanentFailureException.class, TransientFailureException.class})
    @SuppressWarnings("unchecked")
    void shouldCreateExceptionFromMessageAndThrowableOnlyConstructor(Class<? extends Exception>
                                                                 testExceptionClass) throws Exception {

        Constructor<? extends Exception> constructor =
                testExceptionClass.getConstructor(String.class, Throwable.class);
        final String TEST_MESSAGE = "Test Message";
        final Exception testCause = new Exception("Root cause");
        Exception exception = constructor.newInstance(TEST_MESSAGE, testCause);

        assertNotNull(exception);
        assertEquals(TEST_MESSAGE, exception.getMessage());
        assertNotNull(exception.getCause());
        assertEquals(testCause, exception.getCause());
    }
}
