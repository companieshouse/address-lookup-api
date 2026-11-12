package uk.gov.companieshouse.addresslookup.lambda.shared;

import com.amazonaws.services.lambda.runtime.Context;
import com.amazonaws.services.lambda.runtime.RequestHandler;
import com.fasterxml.jackson.core.JsonProcessingException;
import uk.gov.companieshouse.addresslookup.lambda.shared.acquisition.Commands;
import uk.gov.companieshouse.addresslookup.lambda.shared.exception.PermanentFailureException;
import uk.gov.companieshouse.addresslookup.lambda.shared.exception.TransientFailureException;
import uk.gov.companieshouse.addresslookup.lambda.shared.storage.StreamingS3;

public abstract class AbstractRequestHandlerImpl<I, O> implements RequestHandler<I, O> {

    public O handleRequest(I request, Context context) {
        O response = null;
        try {
            response =  requestOperation(request, context);
        } catch (Throwable t) {
            response = handleThrowables(t);
        }
        return response;
    }

    protected abstract O requestOperation(I request, Context context) throws PermanentFailureException, TransientFailureException;

    private O handleThrowables(Throwable t) {
        // Depending upon the Throwable type, throw either handle as a Transient Failure
        // or Permanent Failure
        switch (t) {
            case PermanentFailureException pfe -> handlePermanentException(pfe);
            case TransientFailureException tfe -> handleTransientException(tfe);
            case Commands.CommandDispatchException cde -> handleCommandDispatchException(cde);
            case IllegalArgumentException iae -> handlePermanentException(iae);
            case null -> {}
            default -> handleTransientException(t);
        }
        return failureResponse();
    }

    protected abstract O failureResponse();

    private void handleCommandDispatchException(Commands.CommandDispatchException commandDispatchException)  {
        if (commandDispatchException.getCause() instanceof JsonProcessingException) {
            handlePermanentException(commandDispatchException);
        } else {
            handleTransientException(commandDispatchException);
        }
    }

    protected void handleChecksumMistmach(StreamingS3.ChecksumMismatchException checksumMismatchException) throws Exception {
        /*

        A checksum or size mismatch is ambiguous. It may be a truncated or corrupted download,
        which is transient, or a wrong MD5 in the manifest, which is permanent. One attempt
        cannot tell these apart.

        Treat it as transient, and read ApproximateReceiveCount from the message attributes.

        On the final attempt (receive count equal to maxReceiveCount), treat it as permanent
        instead: mark the release BLOCKED with reason CHECKSUM_MISMATCH, raise an alert,
        and do not report a batch failure.

         */
        handleTransientException(checksumMismatchException);
    }

    protected abstract void handlePermanentException(Throwable permanentException);
    protected abstract void handleTransientException(Throwable transientException);

}
