package uk.gov.companieshouse.addresslookup.lamda.shared.acquisition;

import com.fasterxml.jackson.databind.JsonNode;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import uk.gov.companieshouse.release.model.KeyLayout;

import java.util.LinkedHashMap;
import java.util.Map;

import static uk.gov.companieshouse.addresslookup.lamda.shared.runtime.RuntimeSupport.JSON;

/**
 * Stateless router for GuardDuty scan results. It never touches S3 or stores state; duplicate
 * events are republished and downstream processing must be repeatable. A failed publish throws so
 * that the invocation is retried.
 */
public final class ScanResultsService {
  private static final Logger LOG = LoggerFactory.getLogger(ScanResultsService.class);
  private static final String SOURCE = "aws.guardduty";
  private static final String DETAIL_TYPE = "GuardDuty Malware Protection Object Scan Result";

  private final Commands commands;
  private final String ingestBucket;

  public ScanResultsService(Commands commands, AcquisitionProperties properties) {
    this.commands = commands;
    this.ingestBucket = AcquisitionProperties.required(properties.ingestBucket(), "INGEST_BUCKET");
  }

  public String scan(Map<String, Object> event) throws Exception {
    JsonNode input = JSON.valueToTree(event);
    String eventId = input.path("id").asText("unknown");
    JsonNode detail = input.path("detail");
    JsonNode object = detail.path("s3ObjectDetails");
    String bucket = text(object, "bucketName");
    String key = text(object, "objectKey");
    String versionId = text(object, "versionId");
    String result = text(detail.path("scanResultDetails"), "scanResultStatus");

    KeyLayout.ZipKey zip = null;
    String invalid = null;
    if (!SOURCE.equals(input.path("source").asText())
        || !DETAIL_TYPE.equals(input.path("detail-type").asText())) {
      invalid = "unexpected source or detail type";
    } else if (!ingestBucket.equals(bucket)) {
      invalid = "unknown bucket";
    } else if (versionId == null) {
      invalid = "missing versionId";
    } else {
      try {
        zip = KeyLayout.parseZipKey(key);
      } catch (IllegalArgumentException e) {
        invalid = "invalid ZIP key";
      }
    }

    Map<String, Object> payload = new LinkedHashMap<>();
    if (zip != null) {
      payload.put("releaseId", zip.releaseId());
      payload.put("dataset", zip.dataset().datasetName());
    }
    String decision;
    if (invalid != null) {
      LOG.warn("Rejecting scan event eventId={} reason={}", eventId, invalid);
      decision = reject(payload, "INVALID_INPUT", result);
    } else {
      payload.put("bucket", bucket);
      payload.put("key", key);
      payload.put("versionId", versionId);
      if ("NO_THREATS_FOUND".equals(result)) {
        decision = "ExtractRequested";
        commands.send(decision, payload);
      } else {
        decision = reject(payload, "THREATS_FOUND".equals(result) ? "MALWARE_FOUND" : "SCAN_INCONCLUSIVE", result);
      }
    }
    LOG.info(
        "Scan routed eventId={} releaseId={} dataset={} scanResult={} versionId={} decision={}",
        eventId,
        payload.get("releaseId"),
        payload.get("dataset"),
        result,
        versionId,
        decision);
    return decision;
  }

  private String reject(Map<String, Object> payload, String code, String result) throws Exception {
    payload.put("code", code);
    if (result != null) payload.put("scanResult", result);
    commands.send("StepRejected", payload);
    return "StepRejected:" + code;
  }

  private static String text(JsonNode node, String field) {
    return node.hasNonNull(field) && !node.get(field).asText().isBlank()
        ? node.get(field).asText()
        : null;
  }
}
