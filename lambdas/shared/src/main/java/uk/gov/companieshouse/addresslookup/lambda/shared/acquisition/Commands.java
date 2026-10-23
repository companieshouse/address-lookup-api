package uk.gov.companieshouse.addresslookup.lambda.shared.acquisition;

import com.fasterxml.jackson.core.JsonProcessingException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import software.amazon.awssdk.services.eventbridge.EventBridgeClient;
import software.amazon.awssdk.services.eventbridge.model.PutEventsRequestEntry;

import java.util.Map;

public final class Commands {
  private static final Logger LOG = LoggerFactory.getLogger(Commands.class);
  private static final String INGEST_SOURCE = "uk.gov.companieshouse.address-lookup.ingest";

  private final EventBridgeClient events;
  private final String bus;
  private final com.fasterxml.jackson.databind.ObjectMapper json;

  public Commands(EventBridgeClient events, String bus, com.fasterxml.jackson.databind.ObjectMapper json) {
    this.events = events;
    this.bus = bus;
    this.json = json;
  }

  void send(String action, String planKey, String dataset) throws JsonProcessingException {
    send(action, Map.of("planKey", planKey, "dataset", dataset));
  }

  void send(String action, Map<String, ?> payload) throws JsonProcessingException {
    send(action, payload, "os.acquisition");
  }

  public void sendIngestion(String action, Map<String, ?> payload) throws JsonProcessingException {
    String receipt = null;
    if (LOG.isInfoEnabled()) {
      receipt =
          json.writerWithDefaultPrettyPrinter()
              .writeValueAsString(
                  Map.of(
                      "source", INGEST_SOURCE,
                      "detail-type", action,
                      "detail", payload));
    }
    send(action, payload, INGEST_SOURCE);
    if (receipt != null) {
      LOG.info("Published ingestion event to bus={}:\n{}", bus, receipt);
    }
  }

  private void send(String action, Map<String, ?> payload, String source)
      throws JsonProcessingException {
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
    if (response.failedEntryCount() != 0) {
      throw new EventPublicationException("EventBridge rejected event; invocation must be retried");
    }
  }
}
