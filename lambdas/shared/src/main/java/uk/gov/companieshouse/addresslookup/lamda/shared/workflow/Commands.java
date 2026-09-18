package uk.gov.companieshouse.addresslookup.lamda.shared.workflow;

import software.amazon.awssdk.services.eventbridge.EventBridgeClient;
import software.amazon.awssdk.services.eventbridge.model.PutEventsRequestEntry;

import java.util.Map;

import static uk.gov.companieshouse.addresslookup.lamda.shared.runtime.RuntimeSupport.*;

final class Commands {
    static void send(String action,String planKey,String dataset) throws Exception {
        try (var events = EventBridgeClient.create()) {
            String detail = JSON.writeValueAsString(Map.of("planKey",planKey,"dataset",dataset));
            var response = events.putEvents(b -> b.entries(PutEventsRequestEntry.builder().eventBusName(env("EVENT_BUS"))
                .source("os.acquisition").detailType(action).detail(detail).build()));
            check(response.failedEntryCount() == 0,"EventBridge rejected command; S3 reconciliation will retry");
        }
    }
}
