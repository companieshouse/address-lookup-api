package uk.gov.companieshouse.addresslookup.lambda.shared;

import com.amazonaws.services.lambda.runtime.Context;
import com.amazonaws.services.lambda.runtime.RequestHandler;
import com.fasterxml.jackson.core.JsonProcessingException;
import software.amazon.awssdk.annotations.NotNull;
import software.amazon.awssdk.core.exception.SdkClientException;
import software.amazon.awssdk.services.s3.model.S3Exception;
import uk.gov.companieshouse.addresslookup.lambda.shared.acquisition.Commands;
import uk.gov.companieshouse.addresslookup.lambda.shared.exception.PermanentFailureException;
import uk.gov.companieshouse.addresslookup.lambda.shared.exception.TransientFailureException;

import java.io.IOException;

public abstract class AbstractRequestHandlerImpl<I, O> implements RequestHandler<I, O> {

    public O handleRequest(I request, Context context) {
        O response = null;
        try {
            response =  requestOperation(request, context);
        } catch (Exception exception) {
            response = handleExceptions(exception);
        }
        return response;
    }

    protected abstract O requestOperation(I request, Context context) throws PermanentFailureException, TransientFailureException;

    private O handleExceptions(@NotNull Exception exception) {
        // Depending upon the Throwable type, handle as a Transient Failure
        // or Permanent Failure
        switch (exception) {
            case TransientFailureException pfe -> handleTransientException(pfe);
            case IllegalArgumentException iae -> handlePermanentException(iae);
            case Commands.CommandDispatchException cde -> handleCommandDispatchException(cde);
            case IOException ioe -> handleTransientException(ioe);
            case SdkClientException sdke -> handleTransientException(sdke);
            case S3Exception s3e when s3e.isRetryableException() -> handleTransientException(s3e);
            default -> handlePermanentException(exception);
        }
        return failureResponse();
    }

    protected abstract O failureResponse();

    private void handleCommandDispatchException(Commands.CommandDispatchException commandDispatchException) {
        if (commandDispatchException.getCause() instanceof JsonProcessingException) {
            handlePermanentException(commandDispatchException);
        } else {
            handleTransientException(commandDispatchException);
        }
    }

    protected abstract void handlePermanentException(Exception permanentException);
    protected abstract void handleTransientException(Exception transientException);

}
