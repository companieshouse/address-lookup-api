package uk.gov.companieshouse.addresslookup.lambda.discovery;

import com.amazonaws.services.lambda.runtime.Context;
import com.amazonaws.services.lambda.runtime.RequestHandler;
import com.amazonaws.services.lambda.runtime.events.APIGatewayV2HTTPEvent;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import uk.gov.companieshouse.addresslookup.lambda.discovery.service.DiscoveryService;
import uk.gov.companieshouse.addresslookup.lambda.shared.runtime.AcquisitionApplication;

public final class APIGatewayHTTPHandler implements RequestHandler<APIGatewayV2HTTPEvent,String> {
    private static final Logger LOG = LoggerFactory.getLogger(APIGatewayHTTPHandler.class);

    private final DiscoveryService service;

    public APIGatewayHTTPHandler() {
         this.service = AcquisitionApplication.start(DiscoveryService.class);
    }

    public String handleRequest(APIGatewayV2HTTPEvent event, Context context) {
        try {
            LOG.info("Received API Gateway Event: [{}]", event.getBody());
            return service.discover(event.getBody());
        } catch (Exception e) {
            throw new RuntimeException(String.format("discovery failed; durable state allows retry, event [%s]", event), e);
        }
    }
}
