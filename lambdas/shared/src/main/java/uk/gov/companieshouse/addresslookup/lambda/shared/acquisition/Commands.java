package uk.gov.companieshouse.addresslookup.lambda.shared.acquisition;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;

import software.amazon.awssdk.core.exception.SdkException;
import software.amazon.awssdk.services.eventbridge.EventBridgeClient;
import software.amazon.awssdk.services.eventbridge.model.PutEventsRequestEntry;

import uk.gov.companieshouse.logging.Logger;
import uk.gov.companieshouse.logging.LoggerFactory;

import java.util.Map;

public final class Commands {
    private static final Logger LOG = LoggerFactory.getLogger(Commands.class.getName());
    private static final String SOURCE = "os.acquisition";

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

    public void send(String action, String planKey, String dataset)
            throws CommandDispatchException {
        send(action, Map.of("planKey", planKey, "dataset", dataset));
    }

    private void send(String action, Map<String, ?> payload)
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
                                                    .source(SOURCE)
                                                    .detailType(action)
                                                    .detail(detail)
                                                    .build()));
            if (response.failedEntryCount() != 0) {
                throw new CommandDispatchException(
                        "EventBridge rejected command; invocation must be retried");
            }
            LOG.info(
                    "Published EventBridge command",
                    Map.of("bus", bus, "action", action, "source", SOURCE));
        } catch (SdkException e) {
            throw new CommandDispatchException(
                    "Could not publish EventBridge command; invocation must be retried", e);
        }
    }
}
