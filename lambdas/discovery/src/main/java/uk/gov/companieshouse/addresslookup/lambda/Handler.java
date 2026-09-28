package uk.gov.companieshouse.addresslookup.lambda;

import com.amazonaws.services.lambda.runtime.Context;
import com.amazonaws.services.lambda.runtime.RequestHandler;
import uk.gov.companieshouse.addresslookup.lambda.shared.acquisition.DiscoveryService;
import uk.gov.companieshouse.addresslookup.lambda.shared.runtime.AcquisitionApplication;

import java.util.Map;

public final class Handler implements RequestHandler<Map<String,Object>,String> {
    private final DiscoveryService service =
            AcquisitionApplication.start(DiscoveryService.class);

    public String handleRequest(Map<String, Object> event, Context context) {
        try {
            return service.discover(event, context);
        } catch (Exception e) {
            throw new RuntimeException("discovery failed; durable state allows retry", e);
        }
    }
}
