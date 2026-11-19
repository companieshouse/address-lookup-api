package uk.gov.companieshouse.addresslookup.lambda.download;

import static uk.gov.companieshouse.addresslookup.lambda.shared.runtime.RuntimeSupport.getExceptionText;
import com.amazonaws.services.lambda.runtime.Context;
import com.google.errorprone.annotations.OverridingMethodsMustInvokeSuper;
import software.amazon.awssdk.services.s3.model.S3Exception;
import uk.gov.companieshouse.addresslookup.lambda.download.service.DownloadService;
import uk.gov.companieshouse.addresslookup.lambda.shared.AbstractRequestHandlerImpl;
import uk.gov.companieshouse.addresslookup.lambda.shared.exception.PermanentFailureException;
import uk.gov.companieshouse.addresslookup.lambda.shared.exception.TransientFailureException;
import uk.gov.companieshouse.addresslookup.lambda.shared.runtime.AcquisitionApplication;
import uk.gov.companieshouse.logging.Logger;
import uk.gov.companieshouse.logging.LoggerFactory;

import java.util.Map;

public final class Handler extends AbstractRequestHandlerImpl<Map<String,Object>,String> {
    private final DownloadService service =
            AcquisitionApplication.start(DownloadService.class);

    private static final Logger LOG = LoggerFactory.getLogger(Handler.class.getName());

    @Override
    protected boolean isPermanent(Exception exception) {
        return switch (exception) {
            case S3Exception s3e when s3e.isRetryableException() -> false;
            default -> super.isPermanent(exception);
        };
    }

    @Override
    protected String requestOperation(Map<String, Object> request, Context context) throws PermanentFailureException, TransientFailureException {
        return service.download(request, context);
    }

    @Override
    protected String failureResponse() {
        return "FAILURE";
    }

    @Override
    protected void handlePermanentException(Exception permanentException) {
        LOG.error("An unrecoverable error occurred which attempting the download",
                permanentException,
                Map.of("Error", getExceptionText(permanentException)));
    }

    @Override
    protected void handleTransientException(Exception transientException) {
        LOG.error("download failed; durable state allows retry",
                transientException,
                Map.of("Error", getExceptionText(transientException)));

        throw new RuntimeException("download failed; durable state allows retry",transientException);
    }
}
