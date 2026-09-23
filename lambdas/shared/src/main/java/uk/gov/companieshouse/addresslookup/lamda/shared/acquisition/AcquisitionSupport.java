package uk.gov.companieshouse.addresslookup.lamda.shared.acquisition;

import com.fasterxml.jackson.databind.JsonNode;
import uk.gov.companieshouse.release.model.Dataset;
import uk.gov.companieshouse.release.model.DatasetCatalog;

import java.util.UUID;

import static uk.gov.companieshouse.addresslookup.lamda.shared.runtime.RuntimeSupport.*;

final class AcquisitionSupport {
  static DatasetCatalog.Table table(String name) throws Exception {
    return Dataset.named(name).table();
  }

  static UUID releaseId(JsonNode d) {
    return UUID.fromString(required(d, "releaseId"));
  }

  static String api(String pkg) {
    check(pkg.matches("[a-zA-Z0-9_-]+"), "Invalid OS package ID");
    return "https://api.os.uk/downloads/v1/dataPackages/" + pkg + "/versions";
  }
}
