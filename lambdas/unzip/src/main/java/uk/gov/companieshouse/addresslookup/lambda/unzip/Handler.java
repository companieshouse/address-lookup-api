package uk.gov.companieshouse.addresslookup.lambda.unzip;

import com.amazonaws.services.lambda.runtime.Context;
import com.amazonaws.services.lambda.runtime.RequestHandler;

import uk.gov.companieshouse.addresslookup.lambda.shared.runtime.AcquisitionApplication;
import uk.gov.companieshouse.addresslookup.lambda.unzip.service.ExtractionReconciler;

import java.util.Map;

public final class Handler implements RequestHandler<Map<String, Object>, String> {
    public static final class UnzipFailureException extends RuntimeException {
        public UnzipFailureException(String message, Throwable cause) {
            super(message, cause);
        }
    }

    private final ExtractionReconciler service =
            AcquisitionApplication.start(ExtractionReconciler.class);

    public String handleRequest(Map<String, Object> event, Context context) {
        try {
            return service.reconcile(context);
        } catch (Exception e) {
            String reason = e.getMessage();
            throw new UnzipFailureException("unzip failed {" + reason + "}", e);
        }
    }
}
