package uk.gov.companieshouse.addresslookup.lambda.shared.acquisition;

import static uk.gov.companieshouse.addresslookup.lambda.shared.runtime.RuntimeSupport.*;

import com.fasterxml.jackson.databind.JsonNode;
import java.util.UUID;
import uk.gov.companieshouse.release.model.Dataset;
import uk.gov.companieshouse.release.model.DatasetCatalog;

public final class AcquisitionSupport {

  private AcquisitionSupport() {}

  public static DatasetCatalog.Table table(String name) throws Exception {
    return DatasetCatalog.table(Dataset.named(name));
  }

  public static UUID releaseId(JsonNode d) {
    return UUID.fromString(required(d, "releaseId"));
  }

  public static String api(String pkg) {
    check(pkg.matches("[a-zA-Z0-9_-]+"), "Invalid OS package ID");
    return "https://api.os.uk/downloads/v1/dataPackages/" + pkg + "/versions";
  }
}
