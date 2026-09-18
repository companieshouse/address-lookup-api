package uk.gov.companieshouse.addresslookup.lamda.shared.workflow;

import com.amazonaws.services.lambda.runtime.Context;
import com.fasterxml.jackson.databind.JsonNode;
import software.amazon.awssdk.services.s3.S3Client;
import uk.gov.companieshouse.addresslookup.lamda.shared.os.OsClient;
import uk.gov.companieshouse.addresslookup.lamda.shared.storage.ReleaseStore;
import uk.gov.companieshouse.release.model.DatasetCatalog;
import uk.gov.companieshouse.release.model.OrderSummary;
import java.time.LocalDate;
import java.util.*;
import java.util.UUID;

import static uk.gov.companieshouse.addresslookup.lamda.shared.runtime.RuntimeSupport.*;
import static uk.gov.companieshouse.addresslookup.lamda.shared.workflow.WorkflowSupport.api;

/** One event-driven workflow step; database changes commit together. */
public final class DiscoveryService {
    public DiscoveryService(Context context) {}
    public String discover(Map<String, Object> event) throws Exception {
        JsonNode input = detail(event); String mode = required(input,"mode");
        check(Set.of("FULL","COU").contains(mode),"Unknown mode");
        JsonNode packages = JSON.readTree(env("OS_PACKAGES")); OsClient os = new OsClient();
        try (var s3 = S3Client.create()) {
            var store = new ReleaseStore(s3); String bucket = env("SOURCE_BUCKET");
            LocalDate previous = null;
            for (String key : store.keys(bucket,"acquisitions/")) {
                JsonNode existing = store.read(bucket,key); boolean complete = true;
                for (var t : DatasetCatalog.tables()) {
                    if (store.receipt(bucket,required(existing,"runId"),t.name(),"download") == null) {
                        Commands.send("DOWNLOAD",key,t.name()); complete = false;
                    }
                }
                if (!complete) return "ACQUISITION_IN_PROGRESS";
                LocalDate date = LocalDate.parse(required(existing,"target"));
                if (previous == null || date.isAfter(previous)) previous = date;
            }
            check(!mode.equals("COU") || previous != null,"COU requires an acquired FULL baseline");
            var candidates = new LinkedHashMap<String, TreeMap<LocalDate, JsonNode>>();
            for (var t : DatasetCatalog.tables()) {
                String url = api(required(packages, t.name()));
                List<JsonNode> versions = new ArrayList<>();
                if (mode.equals("FULL")) {
                    String version = required(input.path("versions"), t.name());
                    check(version.matches("[a-zA-Z0-9_-]+") && !version.equals("latest"), "Pin FULL version ID");
                    versions.add(os.json(url + "/" + version));
                } else {
                    JsonNode listed = os.json(url);
                    check(listed.isArray(), "Unexpected OS version list");
                    for (JsonNode version : listed)
                        if (mode.equals(version.path("supplyType").asText()))
                            versions.add(os.json(url + "/" + required(version, "id")));
                }
                var byDate = new TreeMap<LocalDate, JsonNode>();
                for (JsonNode v : versions) {
                    if (!mode.equals(v.path("supplyType").asText())) continue;
                    JsonNode zip = null, summaryFile = null;
                    for (JsonNode file : v.path("downloads")) {
                        if ((t.name() + ".zip").equals(file.path("fileName").asText())) {
                            check(zip == null, "Duplicate primary ZIP");
                            zip = file;
                        }
                        if ((t.name() + "_orderSummary.json").equals(file.path("fileName").asText())) {
                            check(summaryFile == null, "Duplicate summary");
                            summaryFile = file;
                        }
                    }
                    check(zip != null && summaryFile != null, "Expected primary ZIP and orderSummary downloads; package layout needs a verified adapter");
                    JsonNode summary = os.json(required(summaryFile, "url"));
                    OrderSummary s = JSON.treeToValue(summary, OrderSummary.class);
                    LocalDate target = LocalDate.parse(required(summary, "validToDate"));
                    if (previous != null && !target.isAfter(previous)) continue;
                    if (mode.equals("COU") && !previous.toString().equals(s.validFromDate())) continue;
                    check(summary.path("recordCount").isIntegralNumber() && summary.path("recordCount").canConvertToLong() && s.recordCount() >= 0, "Invalid recordCount");
                    check(t.name().replace('_', '-').equals(s.featureName()) && t.version().equals(s.schemaVersion()) && ("http://www.opengis.net/def/crs/EPSG/0/" + OrderSummary.srid(t)).equals(s.crs()), "OS schema/CRS mismatch");
                    if (mode.equals("COU")) s.validate(t, previous, target);
                    check(required(zip, "md5").matches("(?i)[0-9a-f]{32}"), "Missing OS ZIP MD5");
                    var candidate = JSON.createObjectNode();
                    candidate.set("summary", summary);
                    candidate.set("zip", zip);
                    candidate.put("version", required(v, "id"));
                    candidate.put("package", required(packages, t.name()));
                    check(byDate.put(target, candidate) == null, "Ambiguous OS resupply for same target; operator must reconcile");
                }
                candidates.put(t.name(), byDate);
            }
            var dates = new TreeSet<>(candidates.values().iterator().next().keySet());
            for (var m : candidates.values()) dates.retainAll(m.keySet());
            if (dates.isEmpty()) return "NO_COMMON_CONTIGUOUS_RELEASE";
            LocalDate target = dates.first();
            var manifest = JSON.createObjectNode();
            manifest.put("mode", mode);
            manifest.put("target", target.toString());
            for (var entry : candidates.entrySet()) manifest.set(entry.getKey(), entry.getValue().get(target));
            if (previous != null) manifest.put("previous",previous.toString());
            // Do not use expiring download URLs as release identity.
            var identity = manifest.deepCopy();
            for (var t : DatasetCatalog.tables()) ((com.fasterxml.jackson.databind.node.ObjectNode) identity.path(t.name()).path("zip")).remove("url");
            UUID id = UUID.nameUUIDFromBytes(identity.toString().getBytes(java.nio.charset.StandardCharsets.UTF_8));
            manifest.put("runId",id.toString());
            String key = ReleaseStore.planKey(manifest);
            store.immutable(bucket,key,manifest);
            for (var t : DatasetCatalog.tables()) Commands.send("DOWNLOAD",key,t.name());
            return id.toString();
        }
    }
}
