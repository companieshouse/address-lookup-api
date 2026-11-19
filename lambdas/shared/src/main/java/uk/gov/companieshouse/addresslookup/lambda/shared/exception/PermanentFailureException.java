package uk.gov.companieshouse.addresslookup.lambda.shared.exception;

import uk.gov.companieshouse.release.model.Dataset;

public class PermanentFailureException extends AbstractLambdaFailureException {

    private PermanentFailureException(String description, Dataset dataset,
                                      String messageId, String releaseId, String reasonCode) {
        super(description, dataset, messageId, releaseId, reasonCode);
    }

    private PermanentFailureException(String description, Throwable cause, Dataset dataset,
                                      String messageId, String releaseId, String reasonCode) {
        super(description, cause, dataset, messageId, releaseId, reasonCode);
    }

    public static Builder<PermanentFailureException> builder() {
        return new Builder<>() {
            @Override
            public PermanentFailureException build() {
                return cause == null ?
                        new PermanentFailureException(description, dataset, messageId, releaseId, reasonCode) :
                        new PermanentFailureException(description, cause, dataset, messageId, releaseId, reasonCode);
            }
        };
    }
}
