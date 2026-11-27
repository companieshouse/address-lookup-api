package uk.gov.companieshouse.addresslookup.lambda.download.service;

import static uk.gov.companieshouse.addresslookup.lambda.shared.acquisition.AcquisitionSupport.releaseId;
import static uk.gov.companieshouse.addresslookup.lambda.shared.runtime.RuntimeSupport.JSON;
import static uk.gov.companieshouse.addresslookup.lambda.shared.runtime.RuntimeSupport.check;
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
import uk.gov.companieshouse.release.model.DatasetDefault;
import uk.gov.companieshouse.release.model.ReleaseManifest;
import uk.gov.companieshouse.release.model.SupplyType;

@Component
public final class DownloadService {
    private static final Logger LOG = LoggerFactory.getLogger(DownloadService.class.getName());
    private static final String DOWNLOAD_COMMAND_DATASET = "dataset";
    private static final String MANIFEST_EVENT_FIELD =  "manifest";
    private static final String LOG_RELEASE_ID = "release_id";

    private static final String CHECKSUM_FAILURE_MESSAGE = "Download failure, checksum mismatch";
    private static final String ZIP_FILE_UPLOAD_ERROR_MESSAGE =
            "Error occurred whilst attempting to verify if the ZIP file has previously been stored"
                + " in the S3 bucket.";

    private static final String REASON_CODE_ZIP_FILE_UPLOAD_FAILURE = "ZIP_FILE_UPLOAD_FAILURE";

    private final S3Client s3;
    private final OsClient os;
    private final AcquisitionProperties properties;

    private final ReleaseStore store;

    public DownloadService(S3Client s3, OsClient os, AcquisitionProperties properties) {
        this.s3 = s3;
        this.os = os;
        this.properties = properties;
        store = new ReleaseStore(s3);
        AcquisitionProperties.required(properties.landingBucket(), "LANDING_BUCKET");
    }

    public String download(Map<String, Object> event, Context context)
            throws PermanentFailureException, TransientFailureException {

        JsonNode manifest = validateEvent(event);

        String manifestReleaseId = releaseId(manifest).toString();

        ReleaseManifest releaseManifest = ReleaseManifest.read(manifest);

        KeyLayout.PackageVersion packageVersion = derivePackageVersionFromManifest(releaseManifest);

        String landingBucket = properties.landingBucket();

        StringBuilder response = null;
        for (DatasetDefault dataset : properties.datasets()) {
            String result = processDataSet(dataset.name(), manifestReleaseId, packageVersion, landingBucket,
                    manifest, context);
            if (response == null) {
                response = new StringBuilder(result);
            } else {
                response.append(",").append(result);
            }
        }

        return response == null ? "" : response.toString();
    }

    private String processDataSet(String dataset, String manifestReleaseId, KeyLayout.PackageVersion packageVersion,
                                String landingBucket,JsonNode manifest, Context context
                                ) throws PermanentFailureException, TransientFailureException {
        LOG.info(
                "Download command accepted",
                Map.of(
                        LOG_RELEASE_ID,
                        Objects.toString(manifestReleaseId),
                        DOWNLOAD_COMMAND_DATASET,
                        Objects.toString(dataset)));

        // Read only the dataset requested by the EventBridge command.
        var datasetJsonObj = manifest.path(dataset);
        check(datasetJsonObj.isObject(), "Missing dataset in acquisition plan");

        var datasetZipJsonObj = datasetJsonObj.path("zip");
        String datasetFileName = required(datasetZipJsonObj, "fileName");
        check((dataset + ".zip").equals(datasetFileName), "Unexpected ZIP filename");

        String expectedDatasetMd5 = required(datasetZipJsonObj, "md5");
        check(expectedDatasetMd5.matches("(?i)[0-9a-f]{32}"), "Invalid ZIP MD5");

        String datasetUrl = required(datasetZipJsonObj, "url");

        String zipUploadS3Path =
                KeyLayout.downloadZipKey(packageVersion, Dataset.named(dataset));
        var evidence =
                readChecksumEvidence(store, landingBucket, zipUploadS3Path, dataset);
        if (evidence != null
                && expectedDatasetMd5.equalsIgnoreCase(evidence.path("md5").asText())
                && store.exists(landingBucket, zipUploadS3Path)
                && store.version(landingBucket, zipUploadS3Path)
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
                        Objects.toString(dataset),
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
                        dataset);

        LOG.info(
                "Download completed",
                Map.of(
                        LOG_RELEASE_ID,
                        Objects.toString(manifestReleaseId),
                        DOWNLOAD_COMMAND_DATASET,
                        Objects.toString(dataset),
                        "s3_key",
                        Objects.toString(upload.key())));

        return "ZIP_SCAN";
    }
    // Accepts only EventBridge DOWNLOAD commands with a non-blank textual planKey and dataset.
    private static JsonNode validateEvent(Map<String, Object> event) {
        check(event != null, "Missing download event");

        check(event.containsKey(MANIFEST_EVENT_FIELD) &&
                event.get(MANIFEST_EVENT_FIELD) != null, "Missing manifest entry");

        var input =  deriveManifestFromEvent(event);
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
            throw handleChecksumException(
                    e, manifestReleaseId, downloadCommandDataset, zipUploadS3Path);
        } catch (StreamingS3.ExistingObjectVerificationException exception) {
            throw handleExistingObjectVerificationException(
                    exception, downloadCommandDataset, manifestReleaseId);
        } catch (NoSuchAlgorithmException exception) {
            throw PermanentFailureException.builder()
                    .description(exception.getMessage())
                    .cause(exception)
                    .dataset(Dataset.named(downloadCommandDataset))
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
                        String.format(
                                "Acquisition plan not found planKey=%s dataset=%s",
                                planKey, dataset);
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

    private TransientFailureException handleChecksumException(
            StreamingS3.ChecksumMismatchException exception,
            String releaseId,
            String dataset,
            String key) {
        return TransientFailureException.builder()
                .cause(exception)
                .description(
                        CHECKSUM_FAILURE_MESSAGE
                                + ": "
                                + getExceptionText(exception)
                                + " s3Key="
                                + key)
                .dataset(Dataset.named(dataset))
                .reasonCode("CHECKSUM_MISMATCH")
                .releaseId(releaseId)
                .build();
    }

    private PermanentFailureException handleExistingObjectVerificationException(
            StreamingS3.ExistingObjectVerificationException exception,
            String dataset,
            String releaseId)
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
            case IOException _ -> true;
            case S3Exception s3e -> isStatusRetryable(s3e);
            default -> false;
        };
    }

    private static boolean isStatusRetryable(S3Exception s3Exception) {
        if (s3Exception.statusCode() >= 500) return true;
        if (s3Exception.statusCode() == 429) return true;
        return s3Exception.getCause() instanceof IOException;
    }

    private static JsonNode deriveManifestFromEvent(Map<String, Object> event) {
        JsonNode eventRoot = JSON.valueToTree(event);
        try {
            return JSON.readTree(eventRoot.get(MANIFEST_EVENT_FIELD).asText());
        } catch (IOException ex) {
            throw new IllegalArgumentException("Invalid manifest entry", ex);
        }
    }

    private KeyLayout.PackageVersion derivePackageVersionFromManifest(ReleaseManifest releaseManifest) {
        String versionId = releaseManifest.versionId();
        String packageId = releaseManifest.dataPackageId();
        SupplyType mode = releaseManifest.type();

        KeyLayout.Feed feed = switch(mode) {
            case SupplyType.COU -> KeyLayout.Feed.DAILY_COU;
            default -> KeyLayout.Feed.INITIAL_FULL;
        };

        return new KeyLayout.PackageVersion(feed, packageId, versionId);
    }
}
