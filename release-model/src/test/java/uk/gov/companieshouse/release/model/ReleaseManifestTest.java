package uk.gov.companieshouse.release.model;

import static org.junit.jupiter.api.Assertions.assertNotNull;
import static uk.gov.companieshouse.release.model.utils.TestUtils.jsonFileToJsonNode;
import org.junit.jupiter.api.Test;

public class ReleaseManifestTest {

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
}
