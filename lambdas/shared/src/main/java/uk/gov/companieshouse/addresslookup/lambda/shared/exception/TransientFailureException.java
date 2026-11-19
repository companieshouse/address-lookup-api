package uk.gov.companieshouse.addresslookup.lambda.shared.exception;

import uk.gov.companieshouse.release.model.Dataset;

public class TransientFailureException extends AbstractLambdaFailureException {

    private TransientFailureException(String description, Dataset dataset,
                                      String messageId, String releaseId, String reasonCode) {
        super(description, dataset, messageId, releaseId, reasonCode);
    }

    private TransientFailureException(String description, Throwable cause, Dataset dataset,
                                      String messageId, String releaseId, String reasonCode) {
        super(description, cause, dataset, messageId, releaseId, reasonCode);
    }

    public static Builder<TransientFailureException> builder() {
        return new Builder<>() {
            @Override
            public TransientFailureException build() {
                return cause == null ?
                        new TransientFailureException(description, dataset, messageId, releaseId, reasonCode) :
                        new TransientFailureException(description, cause, dataset, messageId, releaseId, reasonCode);
            }
        };
    }
}
