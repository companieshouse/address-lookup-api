package uk.gov.companieshouse.addresslookup.lambda.shared.sqs;

import static uk.gov.companieshouse.addresslookup.lambda.shared.runtime.RuntimeSupport.JSON;
import com.amazonaws.services.lambda.runtime.events.SQSEvent;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonMappingException;

import java.util.Map;

public class SQSHelper {

    @SuppressWarnings("unchecked")
    public static Map<String, Object> extractMapFromSQSMessageBody(SQSEvent.SQSMessage message) throws JsonProcessingException, JsonMappingException {
        return JSON.readValue(message.getBody(), Map.class);
    }
}
