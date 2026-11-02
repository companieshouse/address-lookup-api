package uk.gov.companieshouse.addresslookup.lambda.scan;

import com.amazonaws.services.lambda.runtime.Context;
import com.amazonaws.services.lambda.runtime.RequestHandler;
import uk.gov.companieshouse.addresslookup.lambda.scan.service.ScanResultsService;
import uk.gov.companieshouse.addresslookup.lambda.shared.runtime.AcquisitionApplication;

import java.util.Map;

public final class Handler implements RequestHandler<Map<String, Object>, String> {
  private final ScanResultsService service =
          AcquisitionApplication.start(ScanResultsService.class);

  public String handleRequest(Map<String, Object> event, Context context) {
    try {
      return service.scan(event);
    } catch (Exception e) {
      throw new ScanProcessingException(e);
    }
  }
}
