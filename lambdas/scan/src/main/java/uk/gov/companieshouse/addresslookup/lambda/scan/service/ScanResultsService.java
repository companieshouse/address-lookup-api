package uk.gov.companieshouse.addresslookup.lambda.scan.service;

import com.fasterxml.jackson.databind.JsonNode;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;
import uk.gov.companieshouse.addresslookup.lambda.scan.InvalidReason;
import uk.gov.companieshouse.addresslookup.lambda.shared.acquisition.AcquisitionProperties;
import uk.gov.companieshouse.addresslookup.lambda.shared.acquisition.Commands;
import uk.gov.companieshouse.addresslookup.lambda.shared.util.KeyLayout;
import uk.gov.companieshouse.addresslookup.lambda.shared.util.KeyLayout.FileType;
import uk.gov.companieshouse.addresslookup.lambda.shared.util.KeyLayout.ObjectKey;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static uk.gov.companieshouse.addresslookup.lambda.shared.runtime.RuntimeSupport.JSON;

/**
 * Stateless router for GuardDuty scan results. It never touches S3 or stores state; duplicate
 * events are republished and downstream processing must be repeatable. A failed publish throws so
 * that the invocation is retried.
 */
@Component
public final class ScanResultsService {
  private static final Logger LOG = LoggerFactory.getLogger(ScanResultsService.class);
  private static final String SOURCE = "aws.guardduty";
  private static final String DETAIL_TYPE = "GuardDuty Malware Protection Object Scan Result";
  private static final String MALWARE_FOUND = "MALWARE_FOUND";

  private final Commands commands;
  private final String ingestBucket;
  private final String cleanBucket;

  public ScanResultsService(Commands commands, AcquisitionProperties properties) {
    this.commands = commands;
    this.ingestBucket = required(properties.ingestBucket(), "INGEST_BUCKET");
    this.cleanBucket = required(properties.scannedBucket(), "SCANNED_BUCKET");
  }

  public String scan(Map<String, Object> event) throws Exception {
    JsonNode input = JSON.valueToTree(event);
    String eventId = text(input, "id");
    JsonNode detail = input.path("detail");
    JsonNode s3Object = detail.path("s3ObjectDetails");
    String bucket = text(s3Object, "bucketName");
    String key = text(s3Object, "objectKey");
    String versionId = text(s3Object, "versionId");
    String result = text(detail.path("scanResultDetails"), "scanResultStatus");
    String objectUri = bucket != null && key != null ? "s3://" + bucket + "/" + key : null;

    Validation validation = validate(input, bucket, key, versionId);
    ObjectKey parsed = validation.parsed();
    InvalidReason invalidReason = validation.reason();

    Map<String, Object> payload = basePayload(parsed);
    if (bucket != null || key != null || versionId != null) {
      payload.put("object", objectReference(bucket, key, versionId));
    }

    String decision;
    String rejectionCode = null;
    if (invalidReason != null) {
      rejectionCode = "INVALID_INPUT";
      decision = reject(payload, parsed, rejectionCode, invalidReason.message(), objectUri);
    } else if ("NO_THREATS_FOUND".equals(result)) {
      decision = switch (parsed.fileType()) {
        case ZIP -> "ExtractRequested";
        case CSV -> "DatasetReady";
        case RECEIPT -> "ReceiptReady";
        case MANIFEST -> "ManifestReady";
      };
      commands.sendIngestion(decision, payload);
    } else {
      rejectionCode = "THREATS_FOUND".equals(result) ? MALWARE_FOUND : "SCAN_INCONCLUSIVE";
      String message =
          MALWARE_FOUND.equals(rejectionCode)
              ? threatNames(detail, result)
              : resultOrUnknown(result);
      decision = reject(payload, parsed, rejectionCode, message, objectUri);
    }

    logRoute(
        rejectionCode,
        eventId,
        parsed,
        result,
        versionId,
        objectUri,
        rejectionCode == null ? decision : "StepRejected:" + rejectionCode,
        rejectionCode == null ? null : payload.get("message"));
    return decision;
  }

  private record Validation(ObjectKey parsed, InvalidReason reason) {}

  private Validation validate(JsonNode input, String bucket, String key, String versionId) {
    ObjectKey parsed;
    try {
      parsed = KeyLayout.parseObjectKey(key);
    } catch (IllegalArgumentException e) {
      parsed = null;
    }
    if (!SOURCE.equals(text(input, "source"))
        || !DETAIL_TYPE.equals(text(input, "detail-type"))) {
      return new Validation(parsed, InvalidReason.UNEXPECTED_SOURCE_OR_DETAIL_TYPE);
    }
    if (parsed == null) return new Validation(null, InvalidReason.INVALID_OBJECT_KEY);
    if (versionId == null) return new Validation(parsed, InvalidReason.MISSING_VERSION_ID);
    String expectedBucket = parsed.fileType() == FileType.CSV ? cleanBucket : ingestBucket;
    return new Validation(
        parsed,
        expectedBucket.equals(bucket) ? null : InvalidReason.UNKNOWN_BUCKET_FOR_OBJECT_KEY);
  }

  private String reject(
      Map<String, Object> payload, ObjectKey parsed, String code, String message, String objectUri) {
    payload.put("step", parsed == null ? "scan" : step(parsed.fileType()));
    payload.put("code", code);
    payload.put("message", message);
    if (objectUri != null) payload.put("objectUri", objectUri);
    commands.sendIngestion("StepRejected", payload);
    return "StepRejected:" + code;
  }

  private static Map<String, Object> basePayload(ObjectKey parsed) {
    Map<String, Object> payload = new LinkedHashMap<>();
    payload.put("schemaVersion", 1);
    if (parsed != null) {
      payload.put("releaseId", parsed.releaseId());
      if (parsed.dataset() != null) payload.put("dataset", parsed.dataset().datasetName());
    }
    return payload;
  }

  private static Map<String, Object> objectReference(String bucket, String key, String versionId) {
    Map<String, Object> object = new LinkedHashMap<>();
    if (bucket != null) object.put("bucket", bucket);
    if (key != null) object.put("key", key);
    if (versionId != null) object.put("versionId", versionId);
    return object;
  }

  private static String step(FileType fileType) {
    return switch (fileType) {
      case ZIP -> "zip-scan";
      case CSV -> "csv-scan";
      case RECEIPT -> "receipt-scan";
      case MANIFEST -> "manifest-scan";
    };
  }

  private static String threatNames(JsonNode detail, String fallback) {
    JsonNode threats = detail.path("scanResultDetails").path("threats");
    List<String> names = new ArrayList<>();
    if (threats.isArray()) {
      for (JsonNode threat : threats) {
        String name =
            threat.isTextual()
                ? threat.asText()
                : firstText(threat, "name", "threatName", "threatType");
        if (name != null) names.add(name);
      }
    } else if (threats.isTextual()) {
      names.add(threats.asText());
    }
    return names.isEmpty() ? resultOrUnknown(fallback) : String.join(", ", names);
  }

  private static String firstText(JsonNode node, String... fields) {
    for (String field : fields) {
      String value = text(node, field);
      if (value != null) return value;
    }
    return null;
  }

  private void logRoute(
      String rejectionCode,
      String eventId,
      ObjectKey parsed,
      String result,
      String versionId,
      String objectUri,
      String decision,
      Object message) {
    String releaseId = parsed == null ? null : parsed.releaseId();
    String dataset =
        parsed == null || parsed.dataset() == null ? null : parsed.dataset().datasetName();
    String fileType = parsed == null ? null : parsed.fileType().name();
    if (MALWARE_FOUND.equals(rejectionCode) || "SCAN_INCONCLUSIVE".equals(rejectionCode)) {
      LOG.error(
          "Scan routed eventId={} releaseId={} dataset={} fileType={} scanResult={} versionId={} objectUri={} decision={} details={}",
          eventId,
          releaseId,
          dataset,
          fileType,
          result,
          versionId,
          objectUri,
          decision,
          message);
    } else {
      LOG.info(
          "Scan routed eventId={} releaseId={} dataset={} fileType={} scanResult={} versionId={} objectUri={} decision={}",
          eventId,
          releaseId,
          dataset,
          fileType,
          result,
          versionId,
          objectUri,
          decision);
    }
  }

  private static String resultOrUnknown(String result) {
    return result == null ? "Missing scan result" : result;
  }

  private static String required(String value, String name) {
    if (value == null || value.isBlank()) throw new IllegalArgumentException("Missing " + name);
    return value;
  }

  private static String text(JsonNode node, String field) {
    return node.hasNonNull(field) && !node.get(field).asText().isBlank()
        ? node.get(field).asText()
        : null;
  }
}
