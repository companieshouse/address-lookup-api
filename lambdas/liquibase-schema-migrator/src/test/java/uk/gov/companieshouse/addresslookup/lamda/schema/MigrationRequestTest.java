package uk.gov.companieshouse.addresslookup.lamda.schema;

import org.junit.jupiter.api.Test;

import java.util.HashMap;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class MigrationRequestTest {

    private static final String SHA = "a".repeat(64);

    @Test
    void parsesAPipelineInvocation() {
        MigrationRequest request = MigrationRequest.from(Map.of("mode", "update", "version", "1.0.3", "sha256", SHA));

        assertEquals(MigrationMode.UPDATE, request.mode());
        assertEquals("1.0.3", request.version());
        assertEquals(SHA, request.sha256());
    }

    @Test
    void rejectsAnUnknownMode() {
        var e = assertThrows(IllegalArgumentException.class,
                () -> MigrationRequest.from(Map.of("mode", "CLEAR_CHECKSUMS", "version", "1.0.3", "sha256", SHA)));
        assertTrue(e.getMessage().contains("Unsupported mode"));
    }

    @Test
    void rejectsAVersionThatCouldReshapeTheObjectKey() {
        assertThrows(IllegalArgumentException.class,
                () -> MigrationRequest.from(Map.of("mode", "STATUS", "version", "../1.0.3", "sha256", SHA)));
        assertThrows(IllegalArgumentException.class,
                () -> MigrationRequest.from(Map.of("mode", "STATUS", "version", "db-schema-1.0.3", "sha256", SHA)));
    }

    @Test
    void requiresTheReleasedDigest() {
        Map<String, Object> event = new HashMap<>(Map.of("mode", "STATUS", "version", "1.0.3"));
        assertThrows(IllegalArgumentException.class, () -> MigrationRequest.from(event));

        event.put("sha256", "A".repeat(64));
        assertThrows(IllegalArgumentException.class, () -> MigrationRequest.from(event));
    }
}
