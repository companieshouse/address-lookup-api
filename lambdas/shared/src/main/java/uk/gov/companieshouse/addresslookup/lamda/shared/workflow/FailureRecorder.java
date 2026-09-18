package uk.gov.companieshouse.addresslookup.lamda.shared.workflow;

import com.fasterxml.jackson.databind.JsonNode;
import uk.gov.companieshouse.addresslookup.lamda.shared.runtime.Connections;
import uk.gov.companieshouse.addresslookup.lamda.shared.runtime.RuntimeSupport;
import uk.gov.companieshouse.addresslookup.releasecore.persistence.ControlRepository;

import java.util.Map;

import static uk.gov.companieshouse.addresslookup.lamda.shared.runtime.RuntimeSupport.detail;


public final class FailureRecorder {
    private final Connections connections;
    public FailureRecorder() { this(RuntimeSupport::connect); }
    public FailureRecorder(Connections connections) { this.connections = connections; }
    public void recordFailure(Map<String, Object> event, String stage, Exception failure) {
        try {
            JsonNode d = detail(event);
            if (!d.hasNonNull("runId")) return;
            String reason = stage + " failed; retry or inspect DLQ (" + failure.getClass().getSimpleName() + ")";
            try (var c = connections.open()) {
                var repository = new ControlRepository(c);
                repository.failureDetail(reason, WorkflowSupport.run(d));
                repository.failureAudit(reason, WorkflowSupport.run(d));
                c.commit();
            }
        } catch (Exception ignored) { /* Preserve original failure; infrastructure may be unavailable. */ }
    }
}
