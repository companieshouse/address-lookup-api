package uk.gov.companieshouse.addresslookup.lambda.discovery;

import com.amazonaws.services.lambda.runtime.Context;
import com.amazonaws.services.lambda.runtime.RequestHandler;
import com.amazonaws.services.lambda.runtime.events.ScheduledEvent;

import uk.gov.companieshouse.addresslookup.lambda.discovery.exception.DiscoveryLambdaException;
import uk.gov.companieshouse.addresslookup.lambda.discovery.service.DiscoveryService;
import uk.gov.companieshouse.addresslookup.lambda.shared.runtime.AcquisitionApplication;
import uk.gov.companieshouse.logging.Logger;
import uk.gov.companieshouse.logging.LoggerFactory;

import java.util.Map;

public class Handler implements RequestHandler<ScheduledEvent, String> {
    private static final Logger LOG = LoggerFactory.getLogger(Handler.class.getName());

    private final DiscoveryService service;

    public Handler() {
        service = AcquisitionApplication.start(DiscoveryService.class);
    }

    @Override
    public String handleRequest(ScheduledEvent scheduledEvent, Context context) {

        try {
            LOG.info("Received scheduled event", Map.of("request_id", requestId(context)));
            return service.discover(scheduledEvent.getDetail());
        } catch (Exception e) {
            LOG.error("Discovery invocation failed", e, Map.of("request_id", requestId(context)));
            throw new DiscoveryLambdaException("Discovery failed; durable state allows retry", e);
        }
    }

    private static String requestId(Context context) {
        String requestId = context.getAwsRequestId();
        return requestId == null ? "unknown" : requestId;
    }
}
