package uk.gov.companieshouse.addresslookup.lambda;

import com.amazonaws.services.lambda.runtime.Context;
import com.amazonaws.services.lambda.runtime.RequestHandler;
import uk.gov.companieshouse.addresslookup.lambda.shared.acquisition.DownloadService;
import uk.gov.companieshouse.addresslookup.lambda.shared.runtime.AcquisitionApplication;

import java.util.Map;

public final class Handler implements RequestHandler<Map<String,Object>,String> {
    private final DownloadService service =
            AcquisitionApplication.start(DownloadService.class);

    public String handleRequest(Map<String, Object> event, Context context) {
        try {
            return service.download(event, context);
        } catch (Exception e) {
            throw new RuntimeException("download failed; durable state allows retry", e);
        }
    }
}
