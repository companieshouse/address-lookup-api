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
import uk.gov.companieshouse.addresslookup.lambda.shared.util.KeyLayout;
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

  private static final String CHECKSUM_FAILURE_MESSAGE = "Download failure, checksum mismatch";
  private static final String ZIP_FILE_UPLOAD_ERROR_MESSAGE =
      "Error occurred whilst attempting to verify if the ZIP file has previously been stored in the S3 bucket.";

  private static final String REASON_CODE_ZIP_FILE_UPLOAD_FAILURE = "ZIP_FILE_UPLOAD_FAILURE";

  private final S3Client s3;
  private final OsClient os;
  private final AcquisitionProperties properties;

  public DownloadService(S3Client s3, OsClient os, AcquisitionProperties properties) {
    this.s3 = s3;
    this.os = os;
    this.properties = properties;
    AcquisitionProperties.required(properties.landingBucket(), "LANDING_BUCKET");
  }

  public String download(Map<String, Object> event, Context context)
      throws PermanentFailureException, TransientFailureException {
    var input = validateCommand(event);

    String downloadCommandPlanKey = input.get(DOWNLOAD_COMMAND_PLAN_KEY).asText().trim();
    String downloadCommandDataset =
        DatasetCatalog.table(Dataset.named(input.get(DOWNLOAD_COMMAND_DATASET).asText().trim()))
            .name();

    var store = new ReleaseStore(s3);
    String landingBucket = properties.landingBucket();

    KeyLayout.PackageVersion packageVersion;
    try {
      packageVersion = KeyLayout.parsePackageVersionManifestKey(downloadCommandPlanKey);
    } catch (IllegalArgumentException e) {
      throw new IllegalArgumentException("Invalid plan key", e);
    }
    var manifest =
        readManifest(store, landingBucket, downloadCommandPlanKey, downloadCommandDataset);
    check(
        packageVersion.packageId().equals(required(manifest, "dataPackageId"))
            && packageVersion.versionId().equals(required(manifest, "versionId")),
        "Manifest package/version does not match its Landing key");

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

    // Read only the dataset requested by the EventBridge command.
    var datasetJsonObj = manifest.path(downloadCommandDataset);
    check(datasetJsonObj.isObject(), "Missing dataset in acquisition plan");

    var datasetZipJsonObj = datasetJsonObj.path("zip");
    String datasetFileName = required(datasetZipJsonObj, "fileName");
    check((downloadCommandDataset + ".zip").equals(datasetFileName), "Unexpected ZIP filename");

    String expectedDatasetMd5 = required(datasetZipJsonObj, "md5");
    check(expectedDatasetMd5.matches("(?i)[0-9a-f]{32}"), "Invalid ZIP MD5");

    String datasetUrl = required(datasetZipJsonObj, "url");
    String zipUploadS3Path =
        KeyLayout.downloadZipKey(packageVersion, Dataset.named(downloadCommandDataset));
    var evidence =
        readChecksumEvidence(store, landingBucket, zipUploadS3Path, downloadCommandDataset);
    if (evidence != null
        && expectedDatasetMd5.equalsIgnoreCase(evidence.path("md5").asText())
        && store.exists(landingBucket, zipUploadS3Path)
        && store
            .version(landingBucket, zipUploadS3Path)
            .equals(evidence.path("versionId").asText())) {
      LOG.info("Download already present", Map.of("s3_key", zipUploadS3Path));
      return "SKIPPED";
    }

    String sanitizedDatasetUrl = sanitizeUrl(datasetUrl);
    LOG.info(
        "Download started",
        Map.of(
            LOG_RELEASE_ID,
            Objects.toString(manifestReleaseId),
            DOWNLOAD_COMMAND_DATASET,
            Objects.toString(downloadCommandDataset),
            "file_name",
            datasetFileName,
            "source_url",
            sanitizedDatasetUrl));

    // Stream the supplier ZIP directly into S3 multipart upload. StreamingS3 calculates the MD5
    // while uploading and completes the upload only when it matches the manifest value.
    StreamingS3.Uploaded upload =
        getUpload(
            datasetUrl,
            context,
            landingBucket,
            zipUploadS3Path,
            expectedDatasetMd5,
            manifestReleaseId,
            downloadCommandDataset);

    LOG.info(
        "Download completed",
        Map.of(
            LOG_RELEASE_ID,
            Objects.toString(manifestReleaseId),
            DOWNLOAD_COMMAND_DATASET,
            Objects.toString(downloadCommandDataset),
            "s3_key",
            Objects.toString(upload.key())));

    return "ZIP_SCAN";
  }

  // Accepts only EventBridge DOWNLOAD commands with a non-blank textual planKey and dataset.
  private static JsonNode validateCommand(Map<String, Object> event) {
    check(event != null, "Missing download event");
    check(
        (!event.containsKey("source") || "os.acquisition".equals(event.get("source")))
            && (!event.containsKey("detail-type") || "DOWNLOAD".equals(event.get("detail-type"))),
        "Unexpected download event");

    check(
        event.get("detail") instanceof Map<?, ?> d && !d.isEmpty(),
        "Missing detail in download command");
    var input = detail(event);

    check(
        input.hasNonNull(DOWNLOAD_COMMAND_PLAN_KEY)
            && input.get(DOWNLOAD_COMMAND_PLAN_KEY).isTextual()
            && !input.get(DOWNLOAD_COMMAND_PLAN_KEY).asText().isBlank(),
        "Missing detail.planKey in download command");
    check(
        input.hasNonNull(DOWNLOAD_COMMAND_DATASET)
            && input.get(DOWNLOAD_COMMAND_DATASET).isTextual()
            && !input.get(DOWNLOAD_COMMAND_DATASET).asText().isBlank(),
        "Missing detail.dataset in download command");
    return input;
  }

  private StreamingS3.Uploaded getUpload(
      String datasetUrl,
      Context context,
      String landingBucket,
      String zipUploadS3Path,
      String expectedDatasetMd5,
      String manifestReleaseId,
      String downloadCommandDataset)
      throws TransientFailureException, PermanentFailureException {

    try (var inputStream = os.download(datasetUrl)) {
      return new StreamingS3(s3, context::getRemainingTimeInMillis)
          .putOverwrite(
              inputStream,
              landingBucket,
              zipUploadS3Path,
              expectedDatasetMd5,
              properties.maxZipBytes());
    } catch (StreamingS3.ChecksumMismatchException e) {
      throw handleChecksumException(e, manifestReleaseId, downloadCommandDataset);
    } catch (StreamingS3.ExistingObjectVerificationException exception) {
      throw handleExistingObjectVerificationException(
          exception, downloadCommandDataset, manifestReleaseId);
    } catch (NoSuchAlgorithmException exception) {
      throw PermanentFailureException.builder()
          .description(exception.getMessage())
          .cause(exception)
          .reasonCode("UNRECOGNISED_DIGEST_SCHEME")
          .releaseId(manifestReleaseId)
          .build();
    } catch (IOException exception) {
      throw TransientFailureException.builder()
          .description(exception.getMessage())
          .cause(exception)
          .dataset(Dataset.named(downloadCommandDataset))
          .reasonCode(REASON_CODE_ZIP_FILE_UPLOAD_FAILURE)
          .releaseId(manifestReleaseId)
          .build();
    } catch (InterruptedException exception) {
      Thread.currentThread().interrupt();
      throw TransientFailureException.builder()
          .description(exception.getMessage())
          .cause(exception)
          .dataset(Dataset.named(downloadCommandDataset))
          .reasonCode(REASON_CODE_ZIP_FILE_UPLOAD_FAILURE)
          .releaseId(manifestReleaseId)
          .build();
    }
  }

  // A missing plan fails the invocation. The plan key has already passed format validation.
  private JsonNode readManifest(
      ReleaseStore store, String landingBucket, String planKey, String dataset)
      throws TransientFailureException {
    try {
      var manifest = store.read(landingBucket, planKey);
      if (manifest == null) {
        String message =
            String.format("Acquisition plan not found planKey=%s dataset=%s", planKey, dataset);
        LOG.error(
            "Acquisition plan not found",
            Map.of("plan_key", planKey, DOWNLOAD_COMMAND_DATASET, dataset));
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

  private JsonNode readChecksumEvidence(
      ReleaseStore store, String landingBucket, String zipKey, String dataset)
      throws TransientFailureException {
    try {
      return store.read(landingBucket, KeyLayout.checksumKey(zipKey));
    } catch (IOException ioException) {
      throw TransientFailureException.builder()
          .description("Failed to read ZIP checksum evidence")
          .dataset(Dataset.named(dataset))
          .cause(ioException)
          .reasonCode("FAILED_TO_READ_CHECKSUM_EVIDENCE")
          .build();
    }
  }

  private PermanentFailureException handleChecksumException(
      StreamingS3.ChecksumMismatchException exception, String releaseId, String dataset) {
    LOG.error(
        "Download checksum mismatch",
        Map.of(
            LOG_RELEASE_ID,
            Objects.toString(releaseId),
            DOWNLOAD_COMMAND_DATASET,
            Objects.toString(dataset),
            "checksum_mismatch",
            getExceptionText(exception)));
    return PermanentFailureException.builder()
        .cause(exception)
        .description(CHECKSUM_FAILURE_MESSAGE)
        .dataset(Dataset.named(dataset))
        .reasonCode("CHECKSUM_MISMATCH")
        .releaseId(releaseId)
        .build();
  }

  private PermanentFailureException handleExistingObjectVerificationException(
      StreamingS3.ExistingObjectVerificationException exception, String dataset, String releaseId)
      throws TransientFailureException {

    if (isExistingObjectVerificationExceptionTransient(exception)) {
      throw TransientFailureException.builder()
          .description(ZIP_FILE_UPLOAD_ERROR_MESSAGE)
          .cause(exception.getCause())
          .dataset(Dataset.named(dataset))
          .reasonCode(REASON_CODE_ZIP_FILE_UPLOAD_FAILURE)
          .releaseId(releaseId)
          .build();
    } else {
      return PermanentFailureException.builder()
          .description(ZIP_FILE_UPLOAD_ERROR_MESSAGE)
          .dataset(Dataset.named(dataset))
          .cause(exception.getCause())
          .reasonCode(REASON_CODE_ZIP_FILE_UPLOAD_FAILURE)
          .releaseId(releaseId)
          .build();
    }
  }

  private static boolean isExistingObjectVerificationExceptionTransient(
      StreamingS3.ExistingObjectVerificationException exception) {
    return switch (exception.getCause()) {
      case IOException ignored -> true;
      case S3Exception s3e -> isStatusRetryable(s3e);
      default -> false;
    };
  }

  private static boolean isStatusRetryable(S3Exception s3Exception) {
    return (s3Exception.statusCode() >= 500);
  }
}
