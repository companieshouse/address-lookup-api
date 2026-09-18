package uk.gov.companieshouse.addresslookup.lamda.discovery;

import com.amazonaws.services.lambda.runtime.Context;
import com.amazonaws.services.lambda.runtime.RequestHandler;
import uk.gov.companieshouse.addresslookup.lamda.shared.workflow.DiscoveryService;
import uk.gov.companieshouse.addresslookup.lamda.shared.workflow.FailureRecorder;

import java.util.Map;

public final class Handler implements RequestHandler<Map<String, Object>, String> {
    @Override
    public String handleRequest(Map<String, Object> event, Context context) {
        DiscoveryService workflow = new DiscoveryService(context);
        try {
            return workflow.discover(event);
        } catch (Exception e) {
            new FailureRecorder().recordFailure(event, "discovery", e);
            throw new RuntimeException("discovery failed; event may be retried", e);
        }
    }
}
