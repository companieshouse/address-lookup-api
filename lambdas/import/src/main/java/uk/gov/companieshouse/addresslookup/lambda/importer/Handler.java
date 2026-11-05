package uk.gov.companieshouse.addresslookup.lambda.importer;

import com.amazonaws.services.lambda.runtime.Context;
import com.amazonaws.services.lambda.runtime.RequestHandler;

import uk.gov.companieshouse.addresslookup.release.importer.ImportService;
import uk.gov.companieshouse.addresslookup.release.runtime.ImportApplication;

import java.util.Map;

public final class Handler implements RequestHandler<Map<String, Object>, String> {
    private final ImportService service;

    public Handler() {
        service = ImportApplication.start();
    }

    public String handleRequest(Map<String, Object> event, Context context) {
        try {
            return service.importCsv(event, context);
        } catch (Exception e) {
            throw new RuntimeException("import failed; durable state allows retry", e);
        }
    }
}
