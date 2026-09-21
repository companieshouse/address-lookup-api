package uk.gov.companieshouse.addresslookup.lamda.shared.acquisition;

import software.amazon.awssdk.services.eventbridge.EventBridgeClient;
import software.amazon.awssdk.services.eventbridge.model.PutEventsRequestEntry;

import java.util.Map;

import static uk.gov.companieshouse.addresslookup.lamda.shared.runtime.RuntimeSupport.*;

final class Commands {
  private final EventBridgeClient events;
  private final String bus;
  private final com.fasterxml.jackson.databind.ObjectMapper json;

  Commands(EventBridgeClient events, String bus, com.fasterxml.jackson.databind.ObjectMapper json) {
    this.events = events;
    this.bus = bus;
    this.json = json;
  }

  void send(String action, String planKey, String dataset) throws Exception {
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
