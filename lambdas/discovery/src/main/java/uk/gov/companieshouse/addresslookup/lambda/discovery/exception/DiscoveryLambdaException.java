package uk.gov.companieshouse.addresslookup.lambda.discovery.exception;

public class DiscoveryLambdaException extends RuntimeException {

    public DiscoveryLambdaException(String message) {
        super(message);
    }
    public DiscoveryLambdaException(Throwable throwable) {
        super(throwable.getMessage(), throwable);
    }
    public DiscoveryLambdaException(String message, Throwable throwable) {
        super(message, throwable);
    }
}
