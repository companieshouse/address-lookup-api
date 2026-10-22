package uk.gov.companieshouse.addresslookup.lambda.shared.acquisition;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import software.amazon.awssdk.services.eventbridge.EventBridgeClient;
import software.amazon.awssdk.services.eventbridge.model.EventBridgeException;
import software.amazon.awssdk.services.eventbridge.model.PutEventsRequest;
import software.amazon.awssdk.services.eventbridge.model.PutEventsResponse;

import java.util.Map;
import java.util.function.Consumer;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;
import static uk.gov.companieshouse.addresslookup.lambda.shared.runtime.RuntimeSupport.JSON;

final class CommandsTest {
  private final EventBridgeClient events = mock(EventBridgeClient.class);
  private final Commands commands = new Commands(events, "test-bus", JSON);

  @Test
  void sendsCommandsWithAndWithoutPlanKey() throws Exception {
    when(events.putEvents(any(Consumer.class))).thenAnswer(invocation -> {
      Consumer<PutEventsRequest.Builder> configure = invocation.getArgument(0);
      var request = PutEventsRequest.builder();
      configure.accept(request);
      var entry = request.build().entries().getFirst();
      assertEquals("test-bus", entry.eventBusName());
      assertEquals("os.acquisition", entry.source());
      if ("DOWNLOAD".equals(entry.detailType())) {
        assertEquals(Map.of("planKey", "plan.json", "dataset", "dataset"),
            JSON.readValue(entry.detail(), Map.class));
      } else {
        assertEquals("CSV_SCAN", entry.detailType());
        assertEquals(Map.of("dataset", "dataset"), JSON.readValue(entry.detail(), Map.class));
      }
      return PutEventsResponse.builder().failedEntryCount(0).build();
    });

    commands.send("DOWNLOAD", "plan.json", "dataset");
    commands.send("CSV_SCAN", "dataset");
  }

  @Test
  void wrapsSerialisationFailure() throws Exception {
    var json = mock(ObjectMapper.class);
    var failure = new JsonProcessingException("serialisation failed") {};
    when(json.writeValueAsString(any())).thenThrow(failure);
    var failingCommands = new Commands(events, "test-bus", json);

    var thrown = assertThrows(Commands.CommandDispatchException.class,
        () -> failingCommands.send("CSV_SCAN", "dataset"));

    assertEquals(failure, thrown.getCause());
    verifyNoInteractions(events);
  }

  @Test
  void reportsRejectedEntries() {
    when(events.putEvents(any(Consumer.class)))
        .thenReturn(PutEventsResponse.builder().failedEntryCount(1).build());

    var thrown = assertThrows(Commands.CommandDispatchException.class,
        () -> commands.send("CSV_SCAN", "dataset"));

    assertTrue(thrown.getMessage().contains("EventBridge rejected command"));
  }

  @Test
  void wrapsSdkFailure() {
    var failure = EventBridgeException.builder().message("Service unavailable").build();
    when(events.putEvents(any(Consumer.class))).thenThrow(failure);

    var thrown = assertThrows(Commands.CommandDispatchException.class,
        () -> commands.send("DOWNLOAD", "plan.json", "dataset"));

    assertEquals(failure, thrown.getCause());
  }
}
