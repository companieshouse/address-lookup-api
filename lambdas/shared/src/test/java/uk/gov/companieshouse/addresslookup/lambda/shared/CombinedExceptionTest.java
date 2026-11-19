package uk.gov.companieshouse.addresslookup.lambda.shared;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import uk.gov.companieshouse.addresslookup.lambda.shared.exception.AbstractLambdaFailureException;
import uk.gov.companieshouse.addresslookup.lambda.shared.exception.PermanentFailureException;
import uk.gov.companieshouse.addresslookup.lambda.shared.exception.TransientFailureException;
import uk.gov.companieshouse.release.model.Dataset;

class CombinedExceptionTest {

    private final String TEST_DESCRIPTION = "Test Message";
    private final String TEST_MESSAGE_ID = "Test Message Id";
    private final String TEST_RELEASE_ID = "Test Release Id";
    private final String TEST_REASON_CODE = "Test Reason Code";
    private final Dataset TEST_DATASET = Dataset.ADD_GB_ROYALMAILADDRESS;

    @ParameterizedTest
    @ValueSource(classes = {PermanentFailureException.class, TransientFailureException.class})
    @SuppressWarnings("unchecked")
    void shouldCreateExceptionFromMessageOnlyConstructor(Class<? extends Exception>
                                                                 testExceptionClass) throws Exception {

        AbstractLambdaFailureException.Builder<? extends AbstractLambdaFailureException> builder =
                (AbstractLambdaFailureException.Builder<? extends AbstractLambdaFailureException>)
                        (testExceptionClass.getMethod("builder").invoke(null));

        Exception exception = builder.description(TEST_DESCRIPTION)
                .messageId(TEST_MESSAGE_ID)
                .dataset(TEST_DATASET)
                .reasonCode(TEST_REASON_CODE)
                .releaseId(TEST_RELEASE_ID)
                .build();

        assertNotNull(exception);
        assertEquals("Description [Test Message], dataset [ADD_GB_ROYALMAILADDRESS], messageId [Test Message Id], releaseId [Test Release Id], reason code [Test Reason Code]", exception.getMessage());
    }


    @ParameterizedTest
    @ValueSource(classes = {PermanentFailureException.class, TransientFailureException.class})
    @SuppressWarnings("unchecked")
    void shouldCreateExceptionFromMessageAndThrowableOnlyConstructor(Class<? extends Exception>
                                                                 testExceptionClass) throws Exception {

        AbstractLambdaFailureException.Builder<? extends AbstractLambdaFailureException> builder =
                (AbstractLambdaFailureException.Builder<? extends AbstractLambdaFailureException>)
                        (testExceptionClass.getMethod("builder").invoke(null));

        final Exception testCause = new Exception("Root cause");

        Exception exception = builder.description(TEST_DESCRIPTION)
                .cause(testCause)
                .messageId(TEST_MESSAGE_ID)
                .dataset(TEST_DATASET)
                .reasonCode(TEST_REASON_CODE)
                .releaseId(TEST_RELEASE_ID)
                .build();

        assertNotNull(exception);
        assertEquals("Description [Test Message], dataset [ADD_GB_ROYALMAILADDRESS], messageId [Test Message Id], releaseId [Test Release Id], reason code [Test Reason Code]", exception.getMessage());
        assertNotNull(exception.getCause());
        assertEquals(testCause, exception.getCause());
    }
}
