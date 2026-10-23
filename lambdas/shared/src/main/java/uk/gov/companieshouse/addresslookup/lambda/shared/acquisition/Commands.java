package uk.gov.companieshouse.addresslookup.lambda.shared.acquisition;

import static uk.gov.companieshouse.addresslookup.lambda.shared.runtime.RuntimeSupport.JSON;
import static uk.gov.companieshouse.addresslookup.lambda.shared.runtime.RuntimeSupport.check;
import software.amazon.awssdk.services.eventbridge.EventBridgeClient;
import software.amazon.awssdk.services.eventbridge.model.PutEventsRequestEntry;

import java.util.Map;

public final class Commands {
  private final EventBridgeClient events;
  private final String bus;
  private final com.fasterxml.jackson.databind.ObjectMapper json;

  Commands(EventBridgeClient events, String bus) {
    this.events = events;
    this.bus = bus;
    this.json = JSON;
  }

  public void send(String action, String planKey, String dataset) throws Exception {
    String detail = json.writeValueAsString(Map.of("planKey", planKey, "dataset", dataset));
    var response =
        events.putEvents(
            b ->
                b.entries(
                    PutEventsRequestEntry.builder()
                        .eventBusName(bus)
                        .source("os.acquisition")
                        .detailType(action)
                        .detail(detail)
                        .build()));
    check(
        response.failedEntryCount() == 0,
        "EventBridge rejected command; S3 reconciliation will retry");
  }
}
