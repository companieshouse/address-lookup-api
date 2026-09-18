package uk.gov.companieshouse.addresslookup.lamda.shared.workflow;

import com.fasterxml.jackson.databind.JsonNode;
import uk.gov.companieshouse.addresslookup.releasecore.domain.DatasetCatalog;
import uk.gov.companieshouse.addresslookup.releasecore.persistence.ControlRepository;

import java.sql.Connection;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;

import static uk.gov.companieshouse.addresslookup.lamda.shared.runtime.RuntimeSupport.check;
import static uk.gov.companieshouse.addresslookup.lamda.shared.runtime.RuntimeSupport.required;

final class WorkflowSupport {
    public static DatasetCatalog.Table table(String name) throws Exception {
        return DatasetCatalog.tables().stream().filter(t -> t.name().equals(name)).findFirst().orElseThrow();
    }

    public static UUID run(JsonNode d) {
        return UUID.fromString(required(d, "runId"));
    }

    public static String str(Map<String, Object> row, String k) {
        return Objects.toString(row.get(k), null);
    }

    public static void enqueue(Connection c, UUID id, String dataset, String action) throws Exception {
        new ControlRepository(c).enqueue(id, dataset, action);
    }

    public static String api(String pkg) {
        check(pkg.matches("[a-zA-Z0-9_-]+"), "Invalid OS package ID");
        return "https://api.os.uk/downloads/v1/dataPackages/" + pkg + "/versions";
    }

    public static Map<String, Object> lockFile(Connection c, JsonNode d, String state) throws Exception {
        Map<String, Object> f = new ControlRepository(c).lockFile(run(d), required(d, "dataset"));
        check(f != null, "Unknown file");
        return state.equals(f.get("state")) && "ACTIVE".equals(f.get("workflow_state")) ? f : null;
    }

    public static void block(Connection c, UUID run, String dataset, String reason) throws Exception {
        new ControlRepository(c).blockFile(run, dataset);
        new ControlRepository(c).blockWorkflow(reason, run);
        new ControlRepository(c).failRun(reason, run);
    }
}
