package uk.gov.companieshouse.addresslookup.lamda.shared.acquisition;

import com.fasterxml.jackson.databind.node.ObjectNode;
import org.junit.jupiter.api.Test;

import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;
import static uk.gov.companieshouse.addresslookup.lamda.shared.runtime.RuntimeSupport.JSON;

class ScanResultsServiceTest {
  private static final String RELEASE = "12345678-1234-1234-1234-123456789abc";
  private static final String KEY = "Scanned/" + RELEASE + "/add_gb_builtaddress.";

  @Test
  void acceptsCleanZipFinding() throws Exception {
    var event = event(KEY + "zip", 0);

    var evidence = ScanResultsService.ScanEvidence.from(event);

    assertEquals("CLEAN", evidence.status);
    assertEquals("ZIP", evidence.fileType);
    assertEquals("add_gb_builtaddress", evidence.dataset);
  }

  @Test
  void treatsMediumAndHighFindingsAsInfected() throws Exception {
    var event = event(KEY + "csv", "MEDIUM");

    var evidence = ScanResultsService.ScanEvidence.from(event);

    assertEquals("INFECTED", evidence.status);
    assertEquals("CSV", evidence.fileType);
  }

  @Test
  void acceptsExplicitCleanScanResult() throws Exception {
    var event =
        (ObjectNode)
            JSON.valueToTree(
                Map.of(
                    "detail",
                    Map.of(
                        "s3ObjectDetails",
                        Map.of("key", KEY + "csv"),
                        "scanResultDetails",
                        Map.of("scanResultStatus", "NO_THREATS_FOUND"))));

    var evidence = ScanResultsService.ScanEvidence.from(event);

    assertEquals("CLEAN", evidence.status);
  }

  @Test
  void ignoresUnsupportedScannedObjects() {
    assertTrue(
        ScanResultsService.ScanEvidence.fromKey(
                "Scanned/" + RELEASE + "/unrecognised.zip", "2026-05-16T09:32:45Z", "CLEAN")
            .isEmpty());
  }

  @Test
  void resetsPublicationWhenScanResultChanges() {
    var evidence =
        ScanResultsService.ScanEvidence.fromKey(KEY + "zip", "2026-05-16T09:32:45Z", "CLEAN")
            .orElseThrow();
    var state =
        new ScanResultsService.ScanState(
            evidence, "CLEAN", true, "2026-05-16T09:32:46Z", false, JSON.createArrayNode());
    var infected =
        ScanResultsService.ScanEvidence.fromKey(
                KEY + "zip", "2026-05-16T09:33:45Z", "INFECTED")
            .orElseThrow();

    state.record(infected);

    assertEquals("INFECTED", state.status);
    assertFalse(state.commandPublished);
  }

  private static ObjectNode event(String key, Object severity) {
    return (ObjectNode)
        JSON.valueToTree(
            Map.of(
                "source",
                "aws.guardduty",
                "time",
                "2026-05-16T09:32:45Z",
                "detail",
                Map.of(
                    "findings",
                    java.util.List.of(
                        Map.of(
                            "severity",
                            severity,
                            "resource",
                            Map.of("s3ObjectDetails", Map.of("key", key)))))));
  }
}
