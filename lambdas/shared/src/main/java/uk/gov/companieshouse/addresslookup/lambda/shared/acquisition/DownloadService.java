package uk.gov.companieshouse.addresslookup.lambda.shared.acquisition;

import com.amazonaws.services.lambda.runtime.Context;
import software.amazon.awssdk.services.s3.S3Client;
import uk.gov.companieshouse.addresslookup.lambda.shared.os.OsClient;
import uk.gov.companieshouse.addresslookup.lambda.shared.storage.ReleaseStore;
import uk.gov.companieshouse.addresslookup.lambda.shared.storage.StreamingS3;

import java.io.InputStream;
import java.util.Map;
import java.util.UUID;
import java.util.logging.Logger;

import static uk.gov.companieshouse.addresslookup.lambda.shared.acquisition.AcquisitionSupport.*;
import static uk.gov.companieshouse.addresslookup.lambda.shared.runtime.RuntimeSupport.*;

public final class DownloadService {
  private static final Logger LOG = Logger.getLogger(DownloadService.class.getName());

  @FunctionalInterface
  interface Downloader {
    InputStream download(String url) throws Exception;
  }

  private final S3Client s3;
  private final Downloader os;
  private final AcquisitionProperties properties;

  public DownloadService(S3Client s3, OsClient os, AcquisitionProperties properties) {
    this(s3, os::download, properties);
  }

  DownloadService(S3Client s3, Downloader os, AcquisitionProperties properties) {
    this.s3 = s3;
    this.os = os;
    this.properties = properties;
  }

  public String download(Map<String, Object> event, Context context) throws Exception {
    // Accept only EventBridge DOWNLOAD commands, while still allowing direct unit-test calls that
    // pass the detail payload only.
    check(
        (!event.containsKey("source") || "os.acquisition".equals(event.get("source")))
            && (!event.containsKey("detail-type") || "DOWNLOAD".equals(event.get("detail-type"))),
        "Unexpected download event");

    var input = detail(event);
    String dataset = table(required(input, "dataset")).name();

    var store = new ReleaseStore(s3);
    String sourceBucket = properties.sourceBucket();
    String planKey = required(input, "planKey");

    // Restrict S3 reads to canonical acquisition manifests.
    check(
        planKey.matches("acquisitions/\\d{4}-\\d{2}-\\d{2}/[0-9a-fA-F-]{36}\\.json"),
        "Invalid plan key");

    var plan = store.read(sourceBucket, planKey);
    check(plan != null, "Missing acquisition plan");

    String id = releaseId(plan).toString();
    LOG.info(
        "Download command accepted releaseId="
            + id
            + " dataset="
            + dataset
            + " planKey="
            + planKey);

    // The receipt makes retries idempotent: if the exact release/dataset was already downloaded,
    // do not fetch the supplier ZIP again.
    if (store.receipt(sourceBucket, id, dataset, "download") != null) {
      LOG.info("Download skipped existing receipt releaseId=" + id + " dataset=" + dataset);
      return "SKIPPED";
    }

    // Read only the dataset requested by the EventBridge command.
    var file = plan.path(dataset);
    check(file.isObject(), "Missing dataset in acquisition plan");

    var zip = file.path("zip");
    String fileName = required(zip, "fileName");
    check((dataset + ".zip").equals(fileName), "Unexpected ZIP filename");

    String expectedMd5 = required(zip, "md5");
    check(expectedMd5.matches("(?i)[0-9a-f]{32}"), "Invalid ZIP MD5");

    String url = required(zip, "url");
    String zipUploadS3Path =
        "quarantine/zips/" + id + "/" + dataset + "/" + UUID.randomUUID() + ".zip";

    LOG.info(
        "Download started releaseId="
            + id
            + " dataset="
            + dataset
            + " fileName="
            + fileName
            + " sourceUrl="
            + sanitizeUrl(url));

    // Stream the supplier ZIP directly into S3 multipart upload. StreamingS3 calculates the MD5
    // while uploading and completes the upload only when it matches the manifest value.
    try (var in = os.download(url)) {
      var upload =
          new StreamingS3(s3, () -> context.getRemainingTimeInMillis())
              .put(in, sourceBucket, zipUploadS3Path, expectedMd5, properties.maxZipBytes());

      // prepare the download receipt
      store.receipt(sourceBucket, id, dataset, "download", upload);

      LOG.info(
          "Download completed releaseId="
              + id
              + " dataset="
              + dataset
              + " s3Key="
              + upload.key()
              + " receiptKey="
              + ReleaseStore.receiptKey(id, dataset, "download"));
    } catch (StreamingS3.ChecksumMismatchException e) {
      LOG.warning(
          "Download checksum mismatch releaseId="
              + id
              + " dataset="
              + dataset
              + " "
              + e.getMessage());
      return "CHECKSUM_MISMATCH";
    }

    return "ZIP_SCAN";
  }
}
