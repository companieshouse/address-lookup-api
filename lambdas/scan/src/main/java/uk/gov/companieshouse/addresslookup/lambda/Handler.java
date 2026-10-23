package uk.gov.companieshouse.addresslookup.lambda;

import com.amazonaws.services.lambda.runtime.Context;
import com.amazonaws.services.lambda.runtime.RequestHandler;
import uk.gov.companieshouse.addresslookup.lambda.scan.ScanApplication;
import uk.gov.companieshouse.addresslookup.lambda.scan.ScanProcessingException;
import uk.gov.companieshouse.addresslookup.lambda.scan.ScanResultsService;

import java.util.Map;

public final class Handler implements RequestHandler<Map<String, Object>, String> {
  private final ScanResultsService service =
      ScanApplication.start();

  public String handleRequest(Map<String, Object> event, Context context) {
    try {
      return service.scan(event);
    } catch (Exception e) {
      throw new ScanProcessingException(e);
    }
  }
}
