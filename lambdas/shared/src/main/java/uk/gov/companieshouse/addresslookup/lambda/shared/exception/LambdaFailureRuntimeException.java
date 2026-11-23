package uk.gov.companieshouse.addresslookup.lambda.shared.exception;

import uk.gov.companieshouse.release.model.Dataset;

public class LambdaFailureRuntimeException extends RuntimeException {

    public LambdaFailureRuntimeException(String message) {
        super(message);
    }

    public LambdaFailureRuntimeException(String message, Throwable cause) {
        super(message, cause);
    }

}
