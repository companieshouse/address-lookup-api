package uk.gov.companieshouse.addresslookup.lambda.download;

import static uk.gov.companieshouse.addresslookup.lambda.shared.runtime.RuntimeSupport.getExceptionText;

import com.amazonaws.services.lambda.runtime.Context;
import java.util.HashMap;
import java.util.Map;
import software.amazon.awssdk.services.s3.model.S3Exception;
import uk.gov.companieshouse.addresslookup.lambda.download.service.DownloadService;
import uk.gov.companieshouse.addresslookup.lambda.shared.AbstractRequestHandlerImpl;
import uk.gov.companieshouse.addresslookup.lambda.shared.exception.AbstractLambdaFailureException;
import uk.gov.companieshouse.addresslookup.lambda.shared.exception.LambdaFailureRuntimeException;
import uk.gov.companieshouse.addresslookup.lambda.shared.exception.PermanentFailureException;
import uk.gov.companieshouse.addresslookup.lambda.shared.exception.TransientFailureException;
import uk.gov.companieshouse.addresslookup.lambda.shared.runtime.AcquisitionApplication;
import uk.gov.companieshouse.logging.Logger;
import uk.gov.companieshouse.logging.LoggerFactory;
import uk.gov.companieshouse.release.model.Dataset;

public final class Handler extends AbstractRequestHandlerImpl<Map<String, Object>, String> {
  private final DownloadService service = AcquisitionApplication.start(DownloadService.class);

  private static final Logger LOG = LoggerFactory.getLogger(Handler.class.getName());

  @Override
  protected boolean isPermanent(Exception exception) {
    return switch (exception) {
      case S3Exception s3e when s3e.statusCode() >= 500 -> false;
      default -> super.isPermanent(exception);
    };
  }

  @Override
  protected String requestOperation(Map<String, Object> request, Context context)
      throws PermanentFailureException, TransientFailureException {
    return service.download(request, context);
  }

  @Override
  protected String failureResponse() {
    return "FAILURE";
  }

  @Override
  protected void handlePermanentException(Exception permanentException) {
    LOG.error(
        "An unrecoverable error occurred which attempting the download",
        permanentException,
        failureLogFields(permanentException, "PERMANENT_FAILURE"));

    throw new LambdaFailureRuntimeException(
        "Download failed and a retry will not succeed", permanentException);
  }

  @Override
  protected void handleTransientException(Exception transientException) {
    LOG.error(
        "download failed; durable state allows retry",
        transientException,
        failureLogFields(transientException, "TRANSIENT_FAILURE"));

    throw new LambdaFailureRuntimeException(
        "download failed; durable state allows retry", transientException);
  }

  // Failure exceptions add the reason code, release ID and dataset as separate log fields.
  // A field is left out when its value is absent. Other exceptions only have the outcome and the
  // error text.
  private static Map<String, Object> failureLogFields(Exception exception, String outcome) {
    Map<String, Object> logFields = new HashMap<>();
    logFields.put("outcome", outcome);
    logFields.put("Error", getExceptionText(exception));
    if (exception instanceof AbstractLambdaFailureException failure) {
      String failureReasonCode = failure.reasonCode();
      String failureReleaseId = failure.releaseId();
      Dataset failureDataset = failure.dataset();
      if (failureReasonCode != null) {
        logFields.put("reason_code", failureReasonCode);
      }
      if (failureReleaseId != null) {
        logFields.put("release_id", failureReleaseId);
      }
      if (failureDataset != null) {
        logFields.put("dataset", failureDataset.datasetName());
      }
    }
    return logFields;
  }
}
