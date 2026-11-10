package uk.gov.companieshouse.addresslookup.lambda.download.service;

import static uk.gov.companieshouse.addresslookup.lambda.shared.acquisition.AcquisitionSupport.*;
import static uk.gov.companieshouse.addresslookup.lambda.shared.runtime.RuntimeSupport.*;

import com.amazonaws.services.lambda.runtime.Context;
import com.fasterxml.jackson.databind.JsonNode;
import java.time.Instant;
import java.util.Map;
import org.springframework.stereotype.Component;
import software.amazon.awssdk.services.s3.S3Client;
import uk.gov.companieshouse.addresslookup.lambda.shared.acquisition.AcquisitionProperties;
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

    private final S3Client s3;
    private final OsClient os;
    private final AcquisitionProperties properties;

    public DownloadService(S3Client s3, OsClient os, AcquisitionProperties properties) {
        this.s3 = s3;
        this.os = os;
        this.properties = properties;
        AcquisitionProperties.required(properties.sourceBucket(), "SOURCE_BUCKET");
    }

    public String download(Map<String, Object> event, Context context) throws Exception {
        var input = validateCommand(event);

        String downloadCommandPlanKey = input.get(DOWNLOAD_COMMAND_PLAN_KEY).asText().trim();
        String downloadCommandDataset = DatasetCatalog.table(Dataset.named(input.get(DOWNLOAD_COMMAND_DATASET).asText().trim())).name();

        var store = new ReleaseStore(s3);
        String sourceBucket = properties.sourceBucket();

        validatePlanKey(downloadCommandPlanKey);
        var manifest = readManifest(store, sourceBucket, downloadCommandPlanKey, downloadCommandDataset);

        String manifestReleaseId = releaseId(manifest).toString();
        LOG.info("Download command accepted",
                Map.of(LOG_RELEASE_ID, manifestReleaseId,
                        DOWNLOAD_COMMAND_DATASET, downloadCommandDataset,
                        "plan_key", downloadCommandPlanKey));

        // The receipt makes retries idempotent: if the exact release/dataset was already downloaded,
        // do not fetch the supplier ZIP again.
        if (store.receipt(sourceBucket, manifestReleaseId, downloadCommandDataset, "download") != null) {
            LOG.info("Download skipped because receipt already exists",
                    Map.of(LOG_RELEASE_ID, manifestReleaseId, DOWNLOAD_COMMAND_DATASET, downloadCommandDataset));
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
        LOG.info("Download started",
                Map.of(LOG_RELEASE_ID, manifestReleaseId,
                        DOWNLOAD_COMMAND_DATASET, downloadCommandDataset,
                        "file_name", datasetFileName,
                        "source_url", sanitizedDatasetUrl));

        // Stream the supplier ZIP directly into S3 multipart upload. StreamingS3 calculates the MD5
        // while uploading and completes the upload only when it matches the manifest value.
        try (var inputStream = os.download(datasetUrl)) {
            var upload =
                    new StreamingS3(s3, () -> context.getRemainingTimeInMillis())
                            .put(inputStream, sourceBucket, zipUploadS3Path, expectedDatasetMd5, properties.maxZipBytes());

            // prepare the download receipt
            store.downloadReceipt(
                    sourceBucket,
                    new ReleaseStore.DownloadReceipt(
                            manifestReleaseId, downloadCommandDataset, datasetFileName, expectedDatasetMd5, datasetUrl, Instant.now()),
                    upload);

            String receiptLocationS3Path = ReleaseStore.receiptKey(manifestReleaseId, downloadCommandDataset, "download");
            LOG.info("Download completed",
                    Map.of(LOG_RELEASE_ID, manifestReleaseId,
                            DOWNLOAD_COMMAND_DATASET, downloadCommandDataset,
                            "s3_key", upload.key(),
                            "receipt_key", receiptLocationS3Path));
        } catch (StreamingS3.ChecksumMismatchException e) {
            LOG.error("Download checksum mismatch",
                    e,
                    Map.of(LOG_RELEASE_ID, manifestReleaseId,
                            DOWNLOAD_COMMAND_DATASET, downloadCommandDataset,
                            "checksum_mismatch", e.getMessage()));
            return "CHECKSUM_MISMATCH";
        }

        return "ZIP_SCAN";
    }

    // Accepts only EventBridge DOWNLOAD commands with a non-blank textual planKey and dataset.
    private static JsonNode validateCommand(Map<String, Object> event) {
        check(event != null, "Missing download event");
        check(
                (!event.containsKey("source") || "os.acquisition".equals(event.get("source")))
                        && (!event.containsKey("detail-type") || "DOWNLOAD".equals(event.get("detail-type"))),
                "Unexpected download event");

        check(event.get("detail") instanceof Map<?, ?> d && !d.isEmpty(), "Missing detail in download command");
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

    // Restricts S3 reads to canonical acquisition manifests.
    private static void validatePlanKey(String planKey) {
    check(
        planKey.matches("control/acquisitions/\\d{4}-\\d{2}-\\d{2}/[0-9a-fA-F-]{36}\\.json"),
        "Invalid plan key");
    }

    // A missing plan fails the invocation. The plan key has already passed format validation.
    private static JsonNode readManifest(ReleaseStore store, String bucket, String planKey, String dataset)
            throws Exception {
        var manifest = store.read(bucket, planKey);
        if (manifest == null) {
            String message = String.format(
                    "Acquisition plan not found planKey=%s dataset=%s", planKey, dataset);
            LOG.error("Acquisition plan not found",
                    Map.of("plan_key", planKey, DOWNLOAD_COMMAND_DATASET, dataset));
            check(false, message);
        }
        return manifest;
    }
}
