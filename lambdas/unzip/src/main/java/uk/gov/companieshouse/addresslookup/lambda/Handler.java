package uk.gov.companieshouse.addresslookup.lambda;

import com.amazonaws.services.lambda.runtime.Context;
import com.amazonaws.services.lambda.runtime.RequestHandler;
import uk.gov.companieshouse.addresslookup.lambda.shared.acquisition.ExtractionService;
import uk.gov.companieshouse.addresslookup.lambda.shared.runtime.AcquisitionApplication;

import java.util.Map;

public final class Handler implements RequestHandler<Map<String, Object>, String> {
  private final ExtractionService service =
     AcquisitionApplication.start(ExtractionService.class);

  public String handleRequest(Map<String, Object> event, Context context) {
    try {
      return service.unzip(event, context);
    } catch (Exception e) {
      throw new RuntimeException("unzip failed; durable state allows retry", e);
    }
  }
}
