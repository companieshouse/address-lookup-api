package uk.gov.companieshouse.addresslookup.lambda.download;

import com.amazonaws.services.lambda.runtime.Context;
import uk.gov.companieshouse.addresslookup.lambda.download.service.DownloadService;
import uk.gov.companieshouse.addresslookup.lambda.shared.AbstractRequestHandlerImpl;
import uk.gov.companieshouse.addresslookup.lambda.shared.exception.PermanentFailureException;
import uk.gov.companieshouse.addresslookup.lambda.shared.exception.TransientFailureException;
import uk.gov.companieshouse.addresslookup.lambda.shared.runtime.AcquisitionApplication;
import uk.gov.companieshouse.logging.Logger;
import uk.gov.companieshouse.logging.LoggerFactory;

import java.util.Map;
import java.util.Objects;

public final class Handler extends AbstractRequestHandlerImpl<Map<String,Object>,String> {
    private final DownloadService service =
            AcquisitionApplication.start(DownloadService.class);

    private static final Logger LOG = LoggerFactory.getLogger(Handler.class.getName());

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
                Map.of("Error", Objects.toString(permanentException.getMessage())));
    }

    @Override
    protected void handleTransientException(Exception transientException) {
        LOG.error("download failed; durable state allows retry",
                transientException,
                Map.of("Error", Objects.toString(transientException.getMessage())));

        throw new RuntimeException("download failed; durable state allows retry",transientException);
    }
}
