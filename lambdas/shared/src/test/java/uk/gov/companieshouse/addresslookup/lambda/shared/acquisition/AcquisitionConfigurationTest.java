package uk.gov.companieshouse.addresslookup.lambda.shared.acquisition;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import software.amazon.awssdk.services.eventbridge.EventBridgeClient;
import software.amazon.awssdk.services.s3.S3Client;

import java.time.Duration;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static uk.gov.companieshouse.addresslookup.lambda.shared.runtime.RuntimeSupport.JSON;

@ExtendWith(MockitoExtension.class)
final class AcquisitionConfigurationTest {
    private final AcquisitionConfiguration configuration = new AcquisitionConfiguration();
    @Mock
    private S3Client s3;
    @Mock
    private EventBridgeClient events;

    @Test
    void createsEveryServiceFromValidProperties() {
        var properties = properties("os-key", "event-bus");

        var os = configuration.osClient(properties, JSON);
        var commands = configuration.commands(events, properties, JSON);

        assertNotNull(os);
        assertNotNull(commands);
        assertNotNull(configuration.downloadService(s3, os, properties));
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
                "ingest-bucket",
                eventBus,
                osApiKey,
                "{}",
                1024L,
                null, null,1024L,
                20,
                Duration.ofSeconds(20),
                Duration.ofSeconds(840));
    }
}
