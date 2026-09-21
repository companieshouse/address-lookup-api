package uk.goc.companieshouse.addresslookup.lamda;

import com.amazonaws.services.lambda.runtime.Context;
import com.amazonaws.services.lambda.runtime.RequestHandler;
import uk.gov.companieshouse.addresslookup.lamda.shared.acquisition.ScanResultsService;
import uk.gov.companieshouse.addresslookup.lamda.shared.runtime.AcquisitionApplication;

import java.util.Map;

public final class Handler implements RequestHandler<Map<String, Object>, String> {
  private final ScanResultsService service =
      AcquisitionApplication.start(ScanResultsService.class);

  public String handleRequest(Map<String, Object> event, Context context) {
    try {
      return service.scan(event, context);
    } catch (Exception e) {
      throw new RuntimeException("scan failed; durable state allows retry", e);
    }
  }
}
