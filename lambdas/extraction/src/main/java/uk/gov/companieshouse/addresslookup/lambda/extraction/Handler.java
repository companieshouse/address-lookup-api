package uk.gov.companieshouse.addresslookup.lambda.extraction;

import com.amazonaws.services.lambda.runtime.Context;
import com.amazonaws.services.lambda.runtime.RequestHandler;
import java.util.Map;
import uk.gov.companieshouse.addresslookup.lambda.extraction.service.ExtractionComponent;
import uk.gov.companieshouse.addresslookup.lambda.extraction.service.ExtractionResult;
import uk.gov.companieshouse.addresslookup.lambda.shared.runtime.AcquisitionApplication;

public final class Handler implements RequestHandler<Map<String, Object>, ExtractionResult> {
  public static final class ExtractionFailureException extends RuntimeException {
    public ExtractionFailureException(String message, Throwable cause) {
      super(message, cause);
    }
  }

  private final ExtractionComponent service =
      AcquisitionApplication.start(ExtractionComponent.class);

  public ExtractionResult handleRequest(Map<String, Object> event, Context context) {
    try {
      return service.extract(event, context);
    } catch (Exception e) {
      String reason = e.getMessage();
      throw new ExtractionFailureException("extraction failed {" + reason + "}", e);
    }
  }
}
