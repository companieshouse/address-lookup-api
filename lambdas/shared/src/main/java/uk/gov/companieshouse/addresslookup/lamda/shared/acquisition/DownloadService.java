package uk.gov.companieshouse.addresslookup.lamda.shared.acquisition;

import com.amazonaws.services.lambda.runtime.Context;
import software.amazon.awssdk.services.s3.S3Client;
import uk.gov.companieshouse.addresslookup.lamda.shared.os.OsClient;
import uk.gov.companieshouse.addresslookup.lamda.shared.storage.ReleaseStore;
import uk.gov.companieshouse.addresslookup.lamda.shared.storage.StreamingS3;

import java.util.Map;
import java.util.UUID;

import static uk.gov.companieshouse.addresslookup.lamda.shared.acquisition.AcquisitionSupport.*;
import static uk.gov.companieshouse.addresslookup.lamda.shared.runtime.RuntimeSupport.*;

public final class DownloadService {
  private final S3Client s3;
  private final OsClient os;
  private final AcquisitionProperties properties;

  public DownloadService(S3Client s3, OsClient os, AcquisitionProperties properties) {
    this.s3 = s3;
    this.os = os;
    this.properties = properties;
  }

  public String download(Map<String, Object> event, Context context) throws Exception {
    var input = detail(event);
    String dataset = table(required(input, "dataset")).name();
    var store = new ReleaseStore(s3);
    String bucket = properties.sourceBucket();
    String planKey = required(input, "planKey");
    check(planKey.startsWith("acquisitions/"), "Invalid plan key");
    var plan = store.read(bucket, planKey);
    check(plan != null, "Missing acquisition plan");
    String id = releaseId(plan).toString();
    if (store.receipt(bucket, id, dataset, "download") != null) return "SKIPPED";
    var file = plan.path(dataset);
    String key = "quarantine/zips/" + id + "/" + dataset + "/" + UUID.randomUUID() + ".zip";
    var version = os.json(api(required(file, "package")) + "/" + required(file, "version"));
    String url = downloadUrl(version, dataset, required(file.path("zip"), "md5"));
    try (var in = os.download(url)) {
      var upload =
          new StreamingS3(s3, () -> context.getRemainingTimeInMillis())
              .put(in, bucket, key, required(file.path("zip"), "md5"), properties.maxZipBytes());
      store.receipt(bucket, id, dataset, "download", upload);
    }
    return "ZIP_SCAN";
  }

  static String downloadUrl(
      com.fasterxml.jackson.databind.JsonNode version, String dataset, String expectedMd5) {
    String url = null;
    for (var download : version.path("downloads"))
      if ((dataset + ".zip").equals(download.path("fileName").asText())) {
        check(url == null, "Ambiguous ZIP download");
        check(
            expectedMd5.equalsIgnoreCase(required(download, "md5")),
            "Pinned OS content changed; reconcile resupply");
        url = required(download, "url");
      }
    check(url != null, "Pinned primary ZIP missing");
    return url;
  }
}
