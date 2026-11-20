package uk.gov.companieshouse.addresslookup.lambda.download.service;

import static uk.gov.companieshouse.addresslookup.lambda.shared.acquisition.AcquisitionSupport.releaseId;
import static uk.gov.companieshouse.addresslookup.lambda.shared.runtime.RuntimeSupport.check;
import static uk.gov.companieshouse.addresslookup.lambda.shared.runtime.RuntimeSupport.detail;
import static uk.gov.companieshouse.addresslookup.lambda.shared.runtime.RuntimeSupport.getExceptionText;
import static uk.gov.companieshouse.addresslookup.lambda.shared.runtime.RuntimeSupport.required;
import static uk.gov.companieshouse.addresslookup.lambda.shared.runtime.RuntimeSupport.sanitizeUrl;

import com.amazonaws.services.lambda.runtime.Context;
import com.fasterxml.jackson.databind.JsonNode;

import java.io.IOException;
import java.security.NoSuchAlgorithmException;
import java.time.Instant;
import java.util.Map;
import java.util.Objects;

import org.springframework.stereotype.Component;
import software.amazon.awssdk.services.s3.S3Client;
import software.amazon.awssdk.services.s3.model.S3Exception;
import uk.gov.companieshouse.addresslookup.lambda.shared.acquisition.AcquisitionProperties;
import uk.gov.companieshouse.addresslookup.lambda.shared.exception.PermanentFailureException;
import uk.gov.companieshouse.addresslookup.lambda.shared.exception.TransientFailureException;
import uk.gov.companieshouse.addresslookup.lambda.shared.os.OsClient;
import uk.gov.companieshouse.addresslookup.lambda.shared.storage.ReleaseStore;
import uk.gov.companieshouse.addresslookup.lambda.shared.storage.StreamingS3;
import uk.gov.companieshouse.logging.Logger;
import uk.gov.companieshouse.logging.LoggerFactory;
import uk.gov.companieshouse.release.model.Dataset;
import uk.gov.companieshouse.release.model.DatasetCatalog;

@Component
public final class DownloadService {
  private static final Logger LOG = LoggerFactory.getLogger(DownloadService.class.getName());
  private static final String DOWNLOAD_COMMAND_PLAN_KEY = "planKey";
  private static final String DOWNLOAD_COMMAND_DATASET = "dataset";
  private static final String LOG_RELEASE_ID = "release_id";

  private static final String UPLOAD_FAILED_MESSAGE = "Upload failed";
  private static final String CHECKSUM_FAILURE_MESSAGE = "Download failure, checksum mismatch";
  private static final String ZIP_FILE_UPLOAD_ERROR_MESSAGE = "Error occurred whilst attempting to verify if the ZIP file has previously been stored in the S3 bucket.";

  private static final String REASON_CODE_ZIP_FILE_UPLOAD_FAILURE = "ZIP_FILE_UPLOAD_FAILURE";

  private final S3Client s3;
  private final OsClient os;
  private final AcquisitionProperties properties;

  public DownloadService(S3Client s3, OsClient os, AcquisitionProperties properties) {
    this.s3 = s3;
    this.os = os;
    this.properties = properties;
    AcquisitionProperties.required(properties.sourceBucket(), "SOURCE_BUCKET");
  }

  public String download(Map<String, Object> event, Context context) throws PermanentFailureException, TransientFailureException {
    var input = validateCommand(event);

    String downloadCommandPlanKey = input.get(DOWNLOAD_COMMAND_PLAN_KEY).asText().trim();
    String downloadCommandDataset = DatasetCatalog.table(Dataset.named(input.get(DOWNLOAD_COMMAND_DATASET).asText().trim())).name();

    var store = new ReleaseStore(s3);
    String sourceBucket = properties.sourceBucket();

    validatePlanKey(downloadCommandPlanKey);
    var manifest = readManifest(store, downloadCommandPlanKey, downloadCommandDataset);

    String manifestReleaseId = releaseId(manifest).toString();
    LOG.info(
            "Download command accepted",
            Map.of(
                    LOG_RELEASE_ID,
                    Objects.toString(manifestReleaseId),
                    DOWNLOAD_COMMAND_DATASET,
                    Objects.toString(downloadCommandDataset),
                    "plan_key",
                    downloadCommandPlanKey));

    // The receipt makes retries idempotent: if the exact release/dataset was already downloaded,
    // do not fetch the supplier ZIP again.
    if (!storeReceiptIfNotAlreadyThere(store, manifestReleaseId, downloadCommandDataset)) {
      return "SKIPPED";
    }

    // Read only the dataset requested by the EventBridge command.
    var datasetJsonObj = manifest.path(downloadCommandDataset);
    check(datasetJsonObj.isObject(), "Missing dataset in acquisition plan");

    var datasetZipJsonObj = datasetJsonObj.path("zip");
    String datasetFileName = required(datasetZipJsonObj, "fileName");
    check((downloadCommandDataset + ".zip").equals(datasetFileName), "Unexpected ZIP filename");

    String expectedDatasetMd5 = required(datasetZipJsonObj, "md5");
    check(expectedDatasetMd5.matches("(?i)[0-9a-f]{32}"), "Invalid ZIP MD5");

    String datasetUrl = required(datasetZipJsonObj, "url");

    String zipUploadS3Path = ReleaseStore.downloadZipKey(manifestReleaseId, downloadCommandDataset);

    String sanitizedDatasetUrl = sanitizeUrl(datasetUrl);
    LOG.info("Download started", Map.of(
                    LOG_RELEASE_ID, Objects.toString(manifestReleaseId),
                    DOWNLOAD_COMMAND_DATASET, Objects.toString(downloadCommandDataset),
                    "file_name", datasetFileName,
                    "source_url", sanitizedDatasetUrl));

    // Stream the supplier ZIP directly into S3 multipart upload. StreamingS3 calculates the MD5
    // while uploading and completes the upload only when it matches the manifest value.
    StreamingS3.Uploaded upload = getUpload(datasetUrl, context, sourceBucket, zipUploadS3Path,
            expectedDatasetMd5, manifestReleaseId, downloadCommandDataset);

    // prepare the download receipt
    store.downloadReceipt(
            sourceBucket,
            new ReleaseStore.DownloadReceipt(
                    manifestReleaseId,
                    downloadCommandDataset,
                    datasetFileName,
                    expectedDatasetMd5,
                    datasetUrl,
                    Instant.now()),
            upload);

    String receiptLocationS3Path =
            ReleaseStore.receiptKey(manifestReleaseId, downloadCommandDataset, "download");
    LOG.info("Download completed", Map.of(
                    LOG_RELEASE_ID, Objects.toString(manifestReleaseId),
                    DOWNLOAD_COMMAND_DATASET, Objects.toString(downloadCommandDataset),
                    "s3_key", Objects.toString(upload.key()),
                    "receipt_key", receiptLocationS3Path));

    return "ZIP_SCAN";
  }

  // Accepts only EventBridge DOWNLOAD commands with a non-blank textual planKey and dataset.
  private static JsonNode validateCommand(Map<String, Object> event) {
    check(event != null, "Missing download event");
    check((!event.containsKey("source") || "os.acquisition".equals(event.get("source")))
        && (!event.containsKey("detail-type") || "DOWNLOAD".equals(event.get("detail-type"))),
        "Unexpected download event");

    check(event.get("detail") instanceof Map<?, ?> d && !d.isEmpty(),
            "Missing detail in download command");
    var input = detail(event);

    check(input.hasNonNull(DOWNLOAD_COMMAND_PLAN_KEY)
                    && input.get(DOWNLOAD_COMMAND_PLAN_KEY).isTextual()
                    && !input.get(DOWNLOAD_COMMAND_PLAN_KEY).asText().isBlank(),
            "Missing detail.planKey in download command");
    check(input.hasNonNull(DOWNLOAD_COMMAND_DATASET)
                    && input.get(DOWNLOAD_COMMAND_DATASET).isTextual()
                    && !input.get(DOWNLOAD_COMMAND_DATASET).asText().isBlank(),
            "Missing detail.dataset in download command");
    return input;
  }

  private StreamingS3.Uploaded getUpload(String datasetUrl, Context context, String sourceBucket,
                                         String zipUploadS3Path, String expectedDatasetMd5,
                                         String manifestReleaseId, String downloadCommandDataset)
          throws TransientFailureException, PermanentFailureException {

    try (var inputStream = os.download(datasetUrl)) {
      return new StreamingS3(s3, context::getRemainingTimeInMillis)
              .put(inputStream, sourceBucket,
                      zipUploadS3Path,
                      expectedDatasetMd5,
                      properties.maxZipBytes());
    } catch (StreamingS3.ChecksumMismatchException e) {
      handleChecksumException(e, manifestReleaseId, downloadCommandDataset, zipUploadS3Path);
    } catch (StreamingS3.ExistingObjectVerificationException exception) {
      handleExistingObjectVerificationException(exception, downloadCommandDataset, manifestReleaseId);
    } catch (NoSuchAlgorithmException exception) {
      throw PermanentFailureException.builder()
              .description(exception.getMessage())
              .cause(exception)
              .reasonCode("UNRECOGNISED_DIGEST_SCHEME")
              .releaseId(manifestReleaseId)
              .build();
    } catch (IOException exception) {
      throw TransientFailureException.builder().description(exception.getMessage())
              .cause(exception)
              .dataset(Dataset.named(downloadCommandDataset))
              .reasonCode(REASON_CODE_ZIP_FILE_UPLOAD_FAILURE)
              .releaseId(manifestReleaseId)
              .build();
    } catch (InterruptedException exception) {
      Thread.currentThread().interrupt();
      throw TransientFailureException.builder().description(exception.getMessage())
              .cause(exception)
              .dataset(Dataset.named(downloadCommandDataset))
              .reasonCode(REASON_CODE_ZIP_FILE_UPLOAD_FAILURE)
              .releaseId(manifestReleaseId)
              .build();
    }
    LOG.error(UPLOAD_FAILED_MESSAGE, Map.of(
            LOG_RELEASE_ID,
            Objects.toString(manifestReleaseId),
            DOWNLOAD_COMMAND_DATASET,
            Objects.toString(downloadCommandDataset)));
    throw PermanentFailureException.builder()
            .description(UPLOAD_FAILED_MESSAGE)
            .dataset(Dataset.named(downloadCommandDataset))
            .reasonCode(REASON_CODE_ZIP_FILE_UPLOAD_FAILURE)
            .releaseId(manifestReleaseId)
            .build();
  }

  // Restricts S3 reads to canonical acquisition manifests.
  private static void validatePlanKey(String planKey) {
    check(
            planKey.matches("acquisitions/\\d{4}-\\d{2}-\\d{2}/[0-9a-fA-F-]{36}\\.json"),
            "Invalid plan key");
  }

  // A missing plan fails the invocation. The plan key has already passed format validation.
  private JsonNode readManifest( ReleaseStore store, String planKey, String dataset) throws TransientFailureException {
    String sourceBucket = properties.sourceBucket();
    try {
      var manifest = store.read(sourceBucket, planKey);
      if (manifest == null) {
        String message =
                String.format("Acquisition plan not found planKey=%s dataset=%s", planKey, dataset);
        LOG.error("Acquisition plan not found", Map.of(
                "plan_key", planKey, DOWNLOAD_COMMAND_DATASET, dataset));
        throw new IllegalArgumentException(message);
      }
      return manifest;
    } catch (IOException ioException) {
      throw TransientFailureException.builder()
              .description("Failed to read manifest")
              .dataset(Dataset.named(dataset))
              .cause(ioException)
              .reasonCode("FAILED_TO_READ_MANIFEST")
              .build();
    }
  }

  private boolean storeReceiptIfNotAlreadyThere(ReleaseStore store,
                                   String manifestReleaseId, String downloadCommandDataset)
  throws TransientFailureException {

    String sourceBucket = properties.sourceBucket();

    try {
      if(store.receipt(sourceBucket, manifestReleaseId, downloadCommandDataset,
              "download") != null) {
        LOG.info(
                "Download skipped because receipt already exists",
                Map.of(LOG_RELEASE_ID, Objects.toString(manifestReleaseId),
                        DOWNLOAD_COMMAND_DATASET, Objects.toString(downloadCommandDataset)));
        return false;
      }
    } catch (IOException ioException) {
      throw TransientFailureException.builder()
              .description("Failed to check if manifest already stored.")
              .dataset(Dataset.named(downloadCommandDataset))
              .cause(ioException)
              .reasonCode("FAILED_TO_STORE_MANIFEST")
              .releaseId(manifestReleaseId)
              .build();
    }
    return true;
  }

  private void handleChecksumException(StreamingS3.ChecksumMismatchException exception,
                                       String releaseId, String dataset, String s3Key)
          throws TransientFailureException {
    LOG.error("Download checksum mismatch", exception, Map.of(
            "outcome",
            "TRANSIENT_FAILURE",
            "reason_code",
            "CHECKSUM_MISMATCH",
            LOG_RELEASE_ID,
            Objects.toString(releaseId),
            DOWNLOAD_COMMAND_DATASET,
            Objects.toString(dataset),
            "s3_key",
            Objects.toString(s3Key),
            "checksum_mismatch_detail",
            getExceptionText(exception)));
    throw TransientFailureException.builder()
            .description(CHECKSUM_FAILURE_MESSAGE)
            .dataset(Dataset.named(dataset))
            .reasonCode("CHECKSUM_MISMATCH")
            .releaseId(releaseId)
            .build();
  }

  private void handleExistingObjectVerificationException(StreamingS3.ExistingObjectVerificationException
      exception, String dataset, String releaseId) throws TransientFailureException, PermanentFailureException {

    if (isExistingObjectVerificationExceptionTransient(exception)) {
      throw TransientFailureException.builder()
              .description(ZIP_FILE_UPLOAD_ERROR_MESSAGE)
              .cause(exception.getCause())
              .dataset(Dataset.named(dataset))
              .reasonCode(REASON_CODE_ZIP_FILE_UPLOAD_FAILURE)
              .releaseId(releaseId)
              .build();
    } else {
      throw PermanentFailureException.builder()
              .description(ZIP_FILE_UPLOAD_ERROR_MESSAGE)
              .dataset(Dataset.named(dataset))
              .cause(exception.getCause())
              .reasonCode(REASON_CODE_ZIP_FILE_UPLOAD_FAILURE)
              .releaseId(releaseId)
              .build();
    }
  }

  private boolean isExistingObjectVerificationExceptionTransient(StreamingS3.ExistingObjectVerificationException exception) {
    return switch (exception.getCause()) {
      case IOException ignored -> true;
      case S3Exception s3e when s3e.isRetryableException() -> true;
      default -> false;
    };
  }

}
