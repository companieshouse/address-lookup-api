package uk.gov.companieshouse.addresslookup.lambda.shared.exception;

public class PermanentFailureException extends Exception {

    public PermanentFailureException(String message) {
        super(message);
    }

    public PermanentFailureException(String message, Throwable throwable) {
        super(message, throwable);
    }
}
