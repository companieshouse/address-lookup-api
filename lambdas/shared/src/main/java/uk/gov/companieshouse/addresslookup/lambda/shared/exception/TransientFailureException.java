package uk.gov.companieshouse.addresslookup.lambda.shared.exception;

public class TransientFailureException extends Exception {

    public TransientFailureException(String message) {
        super(message);
    }

    public TransientFailureException(String message, Throwable throwable) {
        super(message, throwable);
    }

}
