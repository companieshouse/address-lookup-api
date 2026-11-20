package uk.gov.companieshouse.addresslookup.lambda.shared.exception;


public class LambdaFailureRuntimeException extends RuntimeException {

    public LambdaFailureRuntimeException(String message) {
        super(message);
    }

    public LambdaFailureRuntimeException(String message, Throwable cause) {
        super(message, cause);
    }

}
