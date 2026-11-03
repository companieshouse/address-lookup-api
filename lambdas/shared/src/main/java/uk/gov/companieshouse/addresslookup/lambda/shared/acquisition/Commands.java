package uk.gov.companieshouse.addresslookup.lambda.shared.acquisition;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import software.amazon.awssdk.core.exception.SdkException;
import software.amazon.awssdk.services.eventbridge.EventBridgeClient;
import software.amazon.awssdk.services.eventbridge.model.PutEventsRequestEntry;

import java.util.Map;

public final class Commands {
  private static final Logger LOG = LoggerFactory.getLogger(Commands.class);
  private static final String INGEST_SOURCE = "uk.gov.companieshouse.address-lookup.ingest";

  private final EventBridgeClient events;
  private final String bus;
  private final ObjectMapper json;

  public static final class CommandDispatchException extends EventPublicationException {
    CommandDispatchException(String message) {
      super(message);
    }

    CommandDispatchException(String message, Throwable cause) {
      super(message, cause);
    }
  }

  Commands(EventBridgeClient events, String bus, ObjectMapper json) {

    this.events = events;
    this.bus = bus;
    this.json = json;
  }

  public void send(String action, String planKey, String dataset) throws CommandDispatchException {
    send(action, Map.of("planKey", planKey, "dataset", dataset), "os.acquisition");
  }

  public void send(String action, String dataset) throws CommandDispatchException {
    send(action, Map.of("dataset", dataset), "os.acquisition");
  }

  public void sendIngestion(String action, Map<String, ?> payload)
      throws CommandDispatchException {
    String receipt = null;
    try {
      if (LOG.isInfoEnabled()) {
        receipt =
            json.writerWithDefaultPrettyPrinter()
                .writeValueAsString(
                    Map.of(
                        "source", INGEST_SOURCE,
                        "detail-type", action,
                        "detail", payload));
      }
    } catch (JsonProcessingException e) {
      throw new CommandDispatchException("Could not serialise EventBridge command", e);
    }
    send(action, payload, INGEST_SOURCE);
    if (receipt != null) {
      LOG.info("Published ingestion event to bus={}:\n{}", bus, receipt);
    }
  }

  private void send(String action, Map<String, ?> payload, String source)
      throws CommandDispatchException {
    String detail;
    try {
      detail = json.writeValueAsString(payload);
    } catch (JsonProcessingException e) {
      throw new CommandDispatchException("Could not serialise EventBridge command", e);
    }

    try {
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
        throw new CommandDispatchException(
            "EventBridge rejected command; invocation must be retried");
      }
    } catch (SdkException e) {
      throw new CommandDispatchException(
          "Could not publish EventBridge command; invocation must be retried", e);
    }
  }
}
