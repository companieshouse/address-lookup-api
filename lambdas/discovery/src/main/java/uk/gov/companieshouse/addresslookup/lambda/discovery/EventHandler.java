package uk.gov.companieshouse.addresslookup.lambda.discovery;

import com.amazonaws.services.lambda.runtime.Context;
import com.amazonaws.services.lambda.runtime.RequestHandler;
import com.amazonaws.services.lambda.runtime.events.ScheduledEvent;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import uk.gov.companieshouse.addresslookup.lambda.discovery.service.DiscoveryService;
import uk.gov.companieshouse.addresslookup.lambda.shared.runtime.AcquisitionApplication;

public class EventHandler implements RequestHandler<ScheduledEvent, String> {
    private static final Logger LOG = LoggerFactory.getLogger(EventHandler.class);

    private final DiscoveryService service;

    public EventHandler() {
        service = AcquisitionApplication.start(DiscoveryService.class);
    }
    @Override
    public String handleRequest(ScheduledEvent scheduledEvent, Context context) {
        try {
            LOG.info("Received Scheduled Event detail: [{}]", scheduledEvent.getDetail());
            return service.discover(scheduledEvent.getDetail());
        } catch (Exception e) {
            throw new RuntimeException(String.format("discovery failed; durable state allows retry, event [%s]", scheduledEvent), e);
        }
    }
}
