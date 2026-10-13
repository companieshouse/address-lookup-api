package uk.gov.companieshouse.addresslookup.lambda.shared.acquisition;

import software.amazon.awssdk.services.eventbridge.EventBridgeClient;
import software.amazon.awssdk.services.eventbridge.model.PutEventsRequestEntry;

import java.util.Map;

import static uk.gov.companieshouse.addresslookup.lambda.shared.runtime.RuntimeSupport.*;

public final class Commands {
  private static final String INGEST_SOURCE = "uk.gov.companieshouse.address-lookup.ingest";

  private final EventBridgeClient events;
  private final String bus;
  private final com.fasterxml.jackson.databind.ObjectMapper json;

  public Commands(EventBridgeClient events, String bus, com.fasterxml.jackson.databind.ObjectMapper json) {
    this.events = events;
    this.bus = bus;
    this.json = json;
  }

  void send(String action, String planKey, String dataset) throws Exception {
    send(action, Map.of("planKey", planKey, "dataset", dataset));
  }

  void send(String action, Map<String, ?> payload) throws Exception {
    send(action, payload, "os.acquisition");
  }

  public void sendIngestion(String action, Map<String, ?> payload) throws Exception {
    send(action, payload, INGEST_SOURCE);
  }

  private void send(String action, Map<String, ?> payload, String source) throws Exception {
    String detail = json.writeValueAsString(payload);
    var response =
        events.putEvents(
            b ->
                b.entries(
                    PutEventsRequestEntry.builder()
                        .eventBusName(bus)
                        .source(source)
                        .detailType(action)
                        .detail(detail)
                        .build()));
    check(
        response.failedEntryCount() == 0,
        "EventBridge rejected event; S3 reconciliation will retry");
  }
}
