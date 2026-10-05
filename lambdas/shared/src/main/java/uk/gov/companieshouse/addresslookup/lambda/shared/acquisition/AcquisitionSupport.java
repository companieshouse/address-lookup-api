package uk.gov.companieshouse.addresslookup.lambda.shared.acquisition;

import com.fasterxml.jackson.databind.JsonNode;
import uk.gov.companieshouse.release.model.Dataset;
import uk.gov.companieshouse.release.model.DatasetCatalog;

import java.util.UUID;

import static uk.gov.companieshouse.addresslookup.lambda.shared.runtime.RuntimeSupport.*;

public final class AcquisitionSupport {
  static DatasetCatalog.Table table(String name) throws Exception {
    return Dataset.named(name).table();
  }

  public static UUID releaseId(JsonNode d) {
    return UUID.fromString(required(d, "releaseId"));
  }

  public static String api(String pkg) {
    check(pkg.matches("[a-zA-Z0-9_-]+"), "Invalid OS package ID");
    return "https://api.os.uk/downloads/v1/dataPackages/" + pkg + "/versions";
  }
}
