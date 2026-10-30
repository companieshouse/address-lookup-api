package uk.gov.companieshouse.addresslookup.lambda.scan.service;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;

import java.time.Duration;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.context.annotation.AnnotationConfigApplicationContext;
import uk.gov.companieshouse.addresslookup.lambda.shared.acquisition.AcquisitionProperties;
import uk.gov.companieshouse.addresslookup.lambda.shared.acquisition.Commands;

@ExtendWith(MockitoExtension.class)
class ScanResultsServiceWiringTest {
    @Mock
    private Commands commands;

    @Test
    void springCreatesServiceFromPropertiesWithoutStringBeansOrSourceBucket() {
        try (var context = new AnnotationConfigApplicationContext()) {
            context.registerBean(Commands.class, () -> commands);
            context.registerBean(AcquisitionProperties.class,
                    () -> properties("ingest-bucket", "scanned-bucket"));
            context.register(ScanResultsService.class);
            context.refresh();

            assertNotNull(context.getBean(ScanResultsService.class));
        }
    }

    @ParameterizedTest
    @CsvSource({
            ", scanned-bucket, INGEST_BUCKET",
            "ingest-bucket, , SCANNED_BUCKET"
    })
    void missingBucketFailsExplicitly(String ingestBucket, String scannedBucket, String name) {
        var properties = properties(ingestBucket, scannedBucket);

        var failure = assertThrows(IllegalArgumentException.class,
                () -> new ScanResultsService(commands, properties));

        assertEquals("Missing " + name, failure.getMessage());
    }

    private static AcquisitionProperties properties(String ingestBucket, String scannedBucket) {
        return new AcquisitionProperties(
                null, scannedBucket, ingestBucket, "event-bus", null, null,
                1024L, null, null, 1024L, 20,
                Duration.ofSeconds(20), Duration.ofSeconds(840));
    }
}
