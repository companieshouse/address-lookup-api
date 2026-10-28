package uk.gov.companieshouse.release.model;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static uk.gov.companieshouse.release.model.utils.TestUtils.jsonFileToJsonNode;
import com.fasterxml.jackson.databind.JsonNode;
import org.junit.jupiter.api.Test;

class ReleaseManifestTest {

    @Test
    void testCOUManifestCreation() throws Exception {
        ReleaseManifest manifest  = ReleaseManifest.read(jsonFileToJsonNode(
                "manifests/output-manifest-COU.json", ReleaseJson.MAPPER));

        assertNotNull(manifest);
    }

    @Test
    void testFullManifestCreation() throws Exception {
        ReleaseManifest manifest  = ReleaseManifest.read(jsonFileToJsonNode(
                "manifests/output-manifest-Full.json", ReleaseJson.MAPPER));

        assertNotNull(manifest);
    }

    @Test
    void testJsonOutput() throws Exception {
        ReleaseManifest manifest  = ReleaseManifest.read(jsonFileToJsonNode(
                "manifests/output-manifest-Full.json", ReleaseJson.MAPPER));

        JsonNode json = manifest.json();

        assertNotNull(json);
        assertEquals("12345678-1234-1234-1234-123456789abc", json.get("releaseId").asText());
    }
}
