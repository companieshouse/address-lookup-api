package uk.gov.companieshouse.addresslookup.lambda.shared.acquisition;

import com.amazonaws.services.lambda.runtime.Context;
import software.amazon.awssdk.services.s3.S3Client;
import uk.gov.companieshouse.addresslookup.lambda.shared.os.OsClient;
import uk.gov.companieshouse.addresslookup.lambda.shared.storage.ReleaseStore;
import uk.gov.companieshouse.addresslookup.lambda.shared.storage.StreamingS3;

import java.time.Instant;
import java.util.Map;
import java.util.logging.Logger;

import static uk.gov.companieshouse.addresslookup.lambda.shared.acquisition.AcquisitionSupport.*;
import static uk.gov.companieshouse.addresslookup.lambda.shared.runtime.RuntimeSupport.*;

public final class DownloadService {
    private static final Logger LOG = Logger.getLogger(DownloadService.class.getName());
    static final String DOWNLOAD_COMPLETE = "DOWNLOAD_COMPLETE";

    private final S3Client s3;
    private final OsClient os;
    private final Commands commands;
    private final AcquisitionProperties properties;

    public DownloadService(S3Client s3, OsClient os, Commands commands, AcquisitionProperties properties) {
        this.s3 = s3;
        this.os = os;
        this.commands = commands;
        this.properties = properties;
    }

    public String download(Map<String, Object> event, Context context) throws Exception {
        // Accept only EventBridge DOWNLOAD commands.
        check(event != null, "Missing download event");
        check(
                (!event.containsKey("source") || "os.acquisition".equals(event.get("source")))
                        && (!event.containsKey("detail-type") || "DOWNLOAD".equals(event.get("detail-type"))),
                "Unexpected download event");

        // The command payload must be a non-empty "detail" object.
        check(event.get("detail") instanceof Map<?, ?> d && !d.isEmpty(), "Missing detail in download command");
        var input = detail(event);

        // The plan key must be present, textual and non-blank.
        check(
                input.hasNonNull("planKey")
                        && input.get("planKey").isTextual()
                        && !input.get("planKey").asText().isBlank(),
                "Missing detail.planKey in download command");
        String downloadCommandPlanKey = input.get("planKey").asText().trim();

        // The dataset must be present, textual and non-blank before it is resolved against the catalogue.
        check(
                input.hasNonNull("dataset")
                        && input.get("dataset").isTextual()
                        && !input.get("dataset").asText().isBlank(),
                "Missing detail.dataset in download command");
        String downloadCommandDataset = table(input.get("dataset").asText().trim()).name();

        var store = new ReleaseStore(s3);
        String sourceBucket = properties.sourceBucket();

        // Restrict S3 reads to canonical acquisition manifests.
        check(
                downloadCommandPlanKey.matches("acquisitions/\\d{4}-\\d{2}-\\d{2}/[0-9a-fA-F-]{36}\\.json"),
                "Invalid plan key");

        var manifest = store.read(sourceBucket, downloadCommandPlanKey);
        check(manifest != null, "Missing acquisition manifest");

        String manifestReleaseId = releaseId(manifest).toString();
        LOG.info(
                "Download command accepted releaseId="
                        + manifestReleaseId
                        + " dataset="
                        + downloadCommandDataset
                        + " planKey="
                        + downloadCommandPlanKey);

        // The receipt makes retries idempotent: if the exact release/dataset was already downloaded,
        // do not fetch the supplier ZIP again.
        if (store.receipt(sourceBucket, manifestReleaseId, downloadCommandDataset, "download") != null) {
            LOG.info("Download skipped existing receipt releaseId=" + manifestReleaseId + " dataset=" + downloadCommandDataset);
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

        LOG.info(
                "Download started releaseId="
                        + manifestReleaseId
                        + " dataset="
                        + downloadCommandDataset
                        + " fileName="
                        + datasetFileName
                        + " sourceUrl="
                        + sanitizeUrl(datasetUrl));

        // Stream the supplier ZIP directly into S3 multipart upload. StreamingS3 calculates the MD5
        // while uploading and completes the upload only when it matches the manifest value.
        try (var inputStream = os.download(datasetUrl)) {
            var upload =
                    new StreamingS3(s3, () -> context.getRemainingTimeInMillis())
                            .put(inputStream, sourceBucket, zipUploadS3Path, expectedDatasetMd5, properties.maxZipBytes());

            // prepare the download receipt
            store.downloadReceipt(
                    sourceBucket, manifestReleaseId, downloadCommandDataset, datasetFileName, expectedDatasetMd5, datasetUrl, Instant.now(), upload);

            LOG.info(
                    "Download completed releaseId="
                            + manifestReleaseId
                            + " dataset="
                            + downloadCommandDataset
                            + " s3Key="
                            + upload.key()
                            + " receiptKey="
                            + ReleaseStore.receiptKey(manifestReleaseId, downloadCommandDataset, "download"));
        } catch (StreamingS3.ChecksumMismatchException e) {
            LOG.warning(
                    "Download checksum mismatch releaseId="
                            + manifestReleaseId
                            + " dataset="
                            + downloadCommandDataset
                            + " "
                            + e.getMessage());
            return "CHECKSUM_MISMATCH";
        }

        // Only reached when the MD5 matched and the receipt was written (conditionally, never overwritten).
        publishDownloadComplete(downloadCommandPlanKey, downloadCommandDataset, manifestReleaseId);
        return "ZIP_SCAN";
    }

    private void publishDownloadComplete(String planKey, String dataset, String releaseId) throws Exception {
        commands.send(DOWNLOAD_COMPLETE, planKey, dataset);
        LOG.info("Download-complete event published releaseId=" + releaseId + " dataset=" + dataset);
    }
}
