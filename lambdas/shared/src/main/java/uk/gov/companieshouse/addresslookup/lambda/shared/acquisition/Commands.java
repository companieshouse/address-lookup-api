package uk.gov.companieshouse.addresslookup.lambda.shared.acquisition;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import software.amazon.awssdk.core.exception.SdkException;
import software.amazon.awssdk.services.eventbridge.EventBridgeClient;
import software.amazon.awssdk.services.eventbridge.model.PutEventsRequestEntry;

import java.util.Map;

final class Commands {
  private final EventBridgeClient events;
  private final String bus;
  private final ObjectMapper json;

  static final class CommandDispatchException extends Exception {
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

  void send(String action, String planKey, String dataset) throws CommandDispatchException {
    send(action, Map.of("planKey", planKey, "dataset", dataset));
  }

  void send(String action, String dataset) throws CommandDispatchException {
    send(action, Map.of("dataset", dataset));
  }

  private void send(String action, Map<String, String> fields) throws CommandDispatchException {
    String detail;
    try {
      detail = json.writeValueAsString(fields);
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
                          .source("os.acquisition")
                          .detailType(action)
                          .detail(detail)
                          .build()));
      if (response.failedEntryCount() != 0) {
        throw new CommandDispatchException(
            "EventBridge rejected command; S3 reconciliation will retry");
      }
    } catch (SdkException e) {
      throw new CommandDispatchException(
          "Could not publish EventBridge command; S3 reconciliation will retry", e);
    }
  }
}
