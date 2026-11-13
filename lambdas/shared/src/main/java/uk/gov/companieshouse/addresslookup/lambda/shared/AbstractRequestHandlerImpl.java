package uk.gov.companieshouse.addresslookup.lambda.shared;

import com.amazonaws.services.lambda.runtime.Context;
import com.amazonaws.services.lambda.runtime.RequestHandler;
import com.fasterxml.jackson.core.JsonProcessingException;
import software.amazon.awssdk.annotations.NotNull;
import uk.gov.companieshouse.addresslookup.lambda.shared.acquisition.Commands;
import uk.gov.companieshouse.addresslookup.lambda.shared.exception.PermanentFailureException;
import uk.gov.companieshouse.addresslookup.lambda.shared.exception.TransientFailureException;

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
        // Depending upon the Throwable type, throw either handle as a Transient Failure
        // or Permanent Failure
        switch (exception) {
            case PermanentFailureException pfe -> handlePermanentException(pfe);
            case TransientFailureException tfe -> handleTransientException(tfe);
            case Commands.CommandDispatchException cde -> handleCommandDispatchException(cde);
            case IllegalArgumentException iae -> handlePermanentException(iae);
            default -> handleTransientException(exception);
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

    protected abstract void handlePermanentException(Throwable permanentException);
    protected abstract void handleTransientException(Throwable transientException);

}
