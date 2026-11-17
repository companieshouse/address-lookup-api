package uk.gov.companieshouse.addresslookup.lambda.test;

import com.amazonaws.services.lambda.runtime.events.SQSEvent;

public class SQSMessageBuilder {

    String messageId;
    String body;

    public static SQSMessageBuilder builder() {
        return new SQSMessageBuilder();
    }

    public SQSMessageBuilder messageId(String id) {
        messageId = id;
        return this;
    }

    public SQSMessageBuilder body(String body) {
        this.body = body;
        return this;
    }

    public SQSEvent.SQSMessage build() {
        SQSEvent.SQSMessage message = new SQSEvent.SQSMessage();
        message.setMessageId(messageId);
        message.setBody(body);
        return message;
    }

}
