package uk.gov.companieshouse.addresslookup.lambda.importer;

import com.amazonaws.services.lambda.runtime.Context;
import com.amazonaws.services.lambda.runtime.RequestHandler;
import uk.gov.companieshouse.addresslookup.lambda.shared.runtime.AcquisitionApplication;
import uk.gov.companieshouse.addresslookup.release.importer.ImportService;

import java.util.Map;

public final class Handler implements RequestHandler<Map<String,Object>,String> {
    private final ImportService service;

    public Handler() {
        service = AcquisitionApplication.start(ImportService.class);
    }

    public String handleRequest(Map<String, Object> event, Context context) {
        try {
            return service.importCsv(event, context);
        } catch (Exception e) {
            throw new RuntimeException("import failed; durable state allows retry", e);
        }
    }
}
