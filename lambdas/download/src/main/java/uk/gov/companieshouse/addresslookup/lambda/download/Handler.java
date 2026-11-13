package uk.gov.companieshouse.addresslookup.lambda.download;

import com.amazonaws.services.lambda.runtime.Context;
import com.amazonaws.services.lambda.runtime.RequestHandler;
import java.util.Map;
import uk.gov.companieshouse.addresslookup.lambda.download.service.DownloadService;
import uk.gov.companieshouse.addresslookup.lambda.shared.runtime.AcquisitionApplication;

public final class Handler implements RequestHandler<Map<String, Object>, String> {
  private final DownloadService service = AcquisitionApplication.start(DownloadService.class);

  public String handleRequest(Map<String, Object> event, Context context) {
    try {
      return service.download(event, context);
    } catch (Exception e) {
      throw new DownloadFailedException(e);
    }
  }
}
