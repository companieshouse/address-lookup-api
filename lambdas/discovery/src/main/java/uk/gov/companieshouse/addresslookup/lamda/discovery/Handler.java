package uk.gov.companieshouse.addresslookup.lamda.discovery;

import com.amazonaws.services.lambda.runtime.Context;
import com.amazonaws.services.lambda.runtime.RequestHandler;
import uk.gov.companieshouse.addresslookup.lamda.shared.workflow.DiscoveryService;

import java.util.Map;

public final class Handler implements RequestHandler<Map<String,Object>,String> {
    public String handleRequest(Map<String,Object> event, Context context) {
        try { return new DiscoveryService(context).discover(event); }
        catch (Exception e) { throw new RuntimeException("discovery failed; durable state allows retry",e); }
    }
}
