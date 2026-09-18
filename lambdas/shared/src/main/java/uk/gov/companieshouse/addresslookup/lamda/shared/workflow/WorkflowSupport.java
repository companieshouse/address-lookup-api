package uk.gov.companieshouse.addresslookup.lamda.shared.workflow;

import com.fasterxml.jackson.databind.JsonNode;
import uk.gov.companieshouse.release.model.Dataset;
import uk.gov.companieshouse.release.model.DatasetCatalog;
import java.util.UUID;

import static uk.gov.companieshouse.addresslookup.lamda.shared.runtime.RuntimeSupport.check;
import static uk.gov.companieshouse.addresslookup.lamda.shared.runtime.RuntimeSupport.required;

final class WorkflowSupport {
    static DatasetCatalog.Table table(String name) throws Exception { return Dataset.named(name).table(); }
    static UUID run(JsonNode d) { return UUID.fromString(required(d, "runId")); }
    static String api(String pkg) {
        check(pkg.matches("[a-zA-Z0-9_-]+"), "Invalid OS package ID");
        return "https://api.os.uk/downloads/v1/dataPackages/" + pkg + "/versions";
    }
}
