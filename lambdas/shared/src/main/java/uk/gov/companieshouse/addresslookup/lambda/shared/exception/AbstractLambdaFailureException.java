package uk.gov.companieshouse.addresslookup.lambda.shared.exception;

import uk.gov.companieshouse.release.model.Dataset;

public abstract class AbstractLambdaFailureException extends Exception {
    private Dataset dataset;
    private String messageId;
    private String reasonCode;
    private String releaseId;
    private String description;

    protected AbstractLambdaFailureException(String description, Dataset dataset,
                                           String messageId, String releaseId, String reasonCode) {
        this.description = description;
        this.dataset = dataset;
        this.reasonCode = reasonCode;
        this.messageId = messageId;
        this.releaseId = releaseId;
    }


    protected AbstractLambdaFailureException(String description, Throwable cause, Dataset dataset,
                                           String messageId, String releaseId, String reasonCode) {
        super(cause);
        this.description = description;
        this.dataset = dataset;
        this.reasonCode = reasonCode;
        this.messageId = messageId;
        this.releaseId = releaseId;
    }

    @Override
    public String getMessage() {
        return String.format("Description [%s], dataset [%s], messageId [%s], releaseId [%s], reason code [%s]",
                description, dataset, messageId, releaseId, reasonCode);
    }

    public Dataset dataset() {
        return dataset;
    }

    public String messageId() {
        return messageId;
    }

    public String reasonCode() {
        return reasonCode;
    }

    public String releaseId() {
        return releaseId;
    }

    public String description() {
        return description;
    }

    public abstract static class Builder<T> {
        protected Dataset dataset;
        protected String messageId;
        protected String reasonCode;
        protected String releaseId;
        protected String description;
        protected Throwable cause;

        public Builder<T> dataset(Dataset dataset) {
            this.dataset = dataset;
            return this;
        }

        public Builder<T> messageId(String messageId) {
            this.messageId = messageId;
            return this;
        }

        public Builder<T> reasonCode(String reasonCode) {
            this.reasonCode = reasonCode;
            return this;
        }

        public Builder<T> releaseId(String releaseId) {
            this.releaseId = releaseId;
            return this;
        }

        public Builder<T> description(String description) {
            this.description = description;
            return this;
        }

        public Builder<T> cause(Throwable cause) {
            this.cause = cause;
            return this;
        }

        public abstract T build();

    }

}
