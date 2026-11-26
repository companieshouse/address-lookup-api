package uk.gov.companieshouse.addresslookup.lambda.unzip.service;

import static uk.gov.companieshouse.addresslookup.lambda.shared.runtime.RuntimeSupport.*;

import com.fasterxml.jackson.databind.JsonNode;
import java.util.Map;

record FullExtractionRequest(String packageId, String versionId) {
  static FullExtractionRequest parse(Map<String, Object> event) {
    check(event != null, "Missing extraction request");
    JsonNode request = JSON.valueToTree(event);
    check("FULL".equals(required(request, "mode")), "Unsupported extraction mode");
    JsonNode versions = request.path("versions");
    String packageId = required(versions, "data_package");
    String versionId = required(versions, "version_id");
    check(packageId.matches("\\d+") && versionId.matches("\\d+"),
        "Invalid package or version ID");
    return new FullExtractionRequest(packageId, versionId);
  }
}
