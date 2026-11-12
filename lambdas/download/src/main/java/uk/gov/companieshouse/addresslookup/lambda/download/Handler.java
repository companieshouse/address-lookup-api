package uk.gov.companieshouse.addresslookup.lambda.download;

import com.amazonaws.services.lambda.runtime.Context;
import software.amazon.awssdk.awscore.exception.AwsServiceException;
import uk.gov.companieshouse.addresslookup.lambda.download.service.DownloadService;
import uk.gov.companieshouse.addresslookup.lambda.shared.AbstractRequestHandlerImpl;
import uk.gov.companieshouse.addresslookup.lambda.shared.exception.PermanentFailureException;
import uk.gov.companieshouse.addresslookup.lambda.shared.exception.TransientFailureException;
import uk.gov.companieshouse.addresslookup.lambda.shared.runtime.AcquisitionApplication;
import uk.gov.companieshouse.logging.Logger;
import uk.gov.companieshouse.logging.LoggerFactory;

public final class Handler implements RequestHandler<Map<String, Object>, String> {
  private final DownloadService service = AcquisitionApplication.start(DownloadService.class);

public final class Handler extends AbstractRequestHandlerImpl<Map<String,Object>,String> {
    private final DownloadService service =
            AcquisitionApplication.start(DownloadService.class);

    private Logger LOG = LoggerFactory.getLogger(Handler.class.getName());
    @Override
    protected String requestOperation(Map<String, Object> request, Context context) throws PermanentFailureException, TransientFailureException {
        return service.download(request, context);
    }

    @Override
    protected String failureResponse() {
        return "FAILURE";
    }

    @Override
    protected void handlePermanentException(Throwable permanentException) {
        LOG.error("An unrecoverable error occurred which attempting the download",
                Map.of("Error", permanentException));
    }

    @Override
    protected void handleTransientException(Throwable transientException) {
        throw AwsServiceException.builder()
                .message("download failed; durable state allows retry")
                .cause(transientException)
                .statusCode(500)
                .build();
    }
  }
}
