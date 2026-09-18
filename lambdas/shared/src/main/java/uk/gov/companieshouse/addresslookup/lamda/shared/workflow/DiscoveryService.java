package uk.gov.companieshouse.addresslookup.lamda.shared.workflow;

import com.amazonaws.services.lambda.runtime.Context;
import com.fasterxml.jackson.databind.JsonNode;

import java.time.LocalDate;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;
import java.util.TreeSet;
import java.util.UUID;

import software.amazon.awssdk.services.s3.S3Client;

import uk.gov.companieshouse.addresslookup.lamda.shared.os.OsClient;
import uk.gov.companieshouse.addresslookup.lamda.shared.runtime.Connections;
import uk.gov.companieshouse.addresslookup.lamda.shared.runtime.RuntimeSupport;
import uk.gov.companieshouse.addresslookup.releasecore.domain.DatasetCatalog;
import uk.gov.companieshouse.addresslookup.releasecore.domain.OrderSummary;
import uk.gov.companieshouse.addresslookup.releasecore.persistence.ControlRepository;

import static uk.gov.companieshouse.addresslookup.lamda.shared.runtime.RuntimeSupport.JSON;
import static uk.gov.companieshouse.addresslookup.lamda.shared.runtime.RuntimeSupport.check;
import static uk.gov.companieshouse.addresslookup.lamda.shared.runtime.RuntimeSupport.detail;
import static uk.gov.companieshouse.addresslookup.lamda.shared.runtime.RuntimeSupport.env;
import static uk.gov.companieshouse.addresslookup.lamda.shared.runtime.RuntimeSupport.required;
import static uk.gov.companieshouse.addresslookup.lamda.shared.runtime.RuntimeSupport.sha;

/** One event-driven workflow step; database changes commit together. */
public final class DiscoveryService {
    private final Context context;
    private final Connections connections;

    public DiscoveryService(Context context) {
        this(context, RuntimeSupport::connect);
    }

    public DiscoveryService(Context context, Connections connections) {
        this.context = context;
        this.connections = connections;
    }

    public String discover(Map<String, Object> event) throws Exception {
        JsonNode input = detail(event);
        String mode = required(input, "mode");
        check(Set.of("FULL", "COU").contains(mode), "Unknown mode");
        JsonNode packages = JSON.readTree(env("OS_PACKAGES"));
        OsClient os = new OsClient();
        try (var c = connections.open()) {
            var repository = new ControlRepository(c);
            repository.lockRelease();
            if (repository.activeCount() > 0)
                return "ACTIVE_RELEASE_EXISTS";
            var water = repository.watermark();
            LocalDate previous = water == null ? null : ((java.sql.Date) water.get("valid_from")).toLocalDate();
            check(!mode.equals("COU") || previous != null, "COU requires a FULL baseline");
            var candidates = new LinkedHashMap<String, TreeMap<LocalDate, JsonNode>>();
            for (var t : DatasetCatalog.tables()) {
                String url = WorkflowSupport.api(required(packages, t.name()));
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
            String payload = JSON.writeValueAsString(manifest), digest = sha(payload.getBytes(java.nio.charset.StandardCharsets.UTF_8));
            String request = mode + ":" + target + ":" + digest;
            if (repository.requestCount(request) > 0)
                return "ALREADY_REGISTERED";
            check(repository.targetCount(target) == 0, "Target already registered with different content; reconcile before retry");
            UUID id = UUID.randomUUID();
            repository.createRun(id, target);
            repository.createWorkflow(id, request, mode, previous, target, digest, payload);
            for (var t : DatasetCatalog.tables()) {
                JsonNode candidate = manifest.get(t.name());
                check(repository.schemaCount(t.name(), t.version()) == 1, "Deployed schema mismatch");
                repository.createFile(id, t.name(), candidate.get("summary").toString(), required(candidate.get("zip"), "url"), required(candidate.get("zip"), "md5"), env("S3_BUCKET"));
                WorkflowSupport.enqueue(c, id, t.name(), "DOWNLOAD");
            }
            // Retain the source metadata alongside objects. Unique key is never reused by another run.
            try (var s3 = S3Client.create()) {
                s3.putObject(b -> b.bucket(env("S3_BUCKET")).key("metadata/" + id + "/manifest.json").ifNoneMatch("*"), software.amazon.awssdk.core.sync.RequestBody.fromString(payload));
            }
            c.commit();
            return id.toString();
        }
    }

}
