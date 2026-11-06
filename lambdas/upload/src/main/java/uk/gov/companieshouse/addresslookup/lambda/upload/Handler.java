package uk.gov.companieshouse.addresslookup.lambda.upload;

import com.amazonaws.services.lambda.runtime.Context;
import com.amazonaws.services.lambda.runtime.RequestHandler;
import com.amazonaws.services.lambda.runtime.events.SQSBatchResponse;
import com.amazonaws.services.lambda.runtime.events.SQSEvent;
import uk.gov.companieshouse.addresslookup.lambda.upload.runtime.UploadApplication;
import uk.gov.companieshouse.addresslookup.lambda.upload.service.UploadService;

public final class Handler implements RequestHandler<SQSEvent, SQSBatchResponse> {
  private final UploadService service;

  public Handler() {
    service = UploadApplication.start();
  }

  public SQSBatchResponse handleRequest(SQSEvent event, Context context) {
    return service.upload(event, context);
  }
}
