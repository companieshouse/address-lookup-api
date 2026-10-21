package uk.gov.companieshouse.addresslookup.lambda.shared.acquisition;

import org.junit.jupiter.api.Test;
import software.amazon.awssdk.services.eventbridge.EventBridgeClient;
import software.amazon.awssdk.services.s3.S3Client;

import java.time.Duration;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.Mockito.mock;
import static uk.gov.companieshouse.addresslookup.lambda.shared.runtime.RuntimeSupport.JSON;

final class AcquisitionConfigurationTest {
    private final AcquisitionConfiguration configuration = new AcquisitionConfiguration();
    private final S3Client s3 = mock(S3Client.class);
    private final EventBridgeClient events = mock(EventBridgeClient.class);

    @Test
    void createsEveryServiceFromValidProperties() {
        var properties = properties("os-key", "event-bus");

        var os = configuration.osClient(properties, JSON);
        var commands = configuration.commands(events, properties, JSON);

        assertNotNull(os);
        assertNotNull(commands);
        assertNotNull(configuration.discoveryService(s3, os, commands, properties));
        assertNotNull(configuration.downloadService(s3, os, commands, properties));
        assertNotNull(configuration.scanResultsService(s3, commands, properties));
        assertNotNull(configuration.extractionService(s3, commands, properties));
    }

    @Test
    void osClientRequiresAnApiKey() {
        var properties = properties(" ", "event-bus");

        var failure = assertThrows(IllegalArgumentException.class, () -> configuration.osClient(properties, JSON));

        assertEquals("Missing OS_API_KEY", failure.getMessage());
    }

    @Test
    void commandsRequireAnEventBus() {
        var properties = properties("os-key", null);

        var failure = assertThrows(
                IllegalArgumentException.class, () -> configuration.commands(events, properties, JSON));

        assertEquals("Missing EVENT_BUS", failure.getMessage());
    }

    private static AcquisitionProperties properties(String osApiKey, String eventBus) {
        return new AcquisitionProperties(
                "source-bucket",
                "scanned-bucket",
                eventBus,
                osApiKey,
                "{}",
                1024L,
                1024L,
                20,
                Duration.ofSeconds(20),
                Duration.ofSeconds(840));
    }
}
