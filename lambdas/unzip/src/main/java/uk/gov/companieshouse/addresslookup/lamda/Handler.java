package uk.gov.companieshouse.addresslookup.lamda;

import com.amazonaws.services.lambda.runtime.Context;
import com.amazonaws.services.lambda.runtime.RequestHandler;
import uk.gov.companieshouse.addresslookup.lamda.shared.acquisition.ExtractionService;
import uk.gov.companieshouse.addresslookup.lamda.shared.runtime.AcquisitionApplication;

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
