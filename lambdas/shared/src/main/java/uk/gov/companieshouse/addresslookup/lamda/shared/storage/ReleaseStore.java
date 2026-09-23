package uk.gov.companieshouse.addresslookup.lamda.shared.storage;

import com.fasterxml.jackson.databind.JsonNode;
import software.amazon.awssdk.core.sync.RequestBody;
import software.amazon.awssdk.services.s3.S3Client;
import software.amazon.awssdk.services.s3.model.*;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

import static uk.gov.companieshouse.addresslookup.lamda.shared.runtime.RuntimeSupport.*;

/** S3 progress records and scan evidence; no database dependency. */
public final class ReleaseStore {
  public static final String CLEAN = "NO_THREATS_FOUND";
  private final S3Client s3;

  public ReleaseStore(S3Client s3) {
    this.s3 = s3;
  }

  public JsonNode read(String bucket, String key) throws Exception {
    // Prefix-scoped ListBucket grants need not reveal missing keys through GET's 404/403
    // distinction.
    // Check the exact permitted key before reading optional receipts.
    var existing =
        s3.listObjectsV2(
            ListObjectsV2Request.builder().bucket(bucket).prefix(key).maxKeys(1).build());
    if (existing.contents().stream().noneMatch(o -> key.equals(o.key()))) return null;
    try (var in = s3.getObject(GetObjectRequest.builder().bucket(bucket).key(key).build())) {
      byte[] bytes = in.readNBytes(1024 * 1024 + 1);
      check(bytes.length <= 1024 * 1024, "Manifest exceeds 1 MiB");
      return JSON.readTree(bytes);
    } catch (S3Exception e) {
      if (e.statusCode() == 404) return null;
      throw e;
    }
  }

  public void immutable(String bucket, String key, JsonNode value) throws Exception {
    try {
      s3.putObject(
          PutObjectRequest.builder()
              .bucket(bucket)
              .key(key)
              .ifNoneMatch("*")
              .contentType("application/json")
              .build(),
          RequestBody.fromString(value.toString()));
    } catch (S3Exception e) {
      if (e.statusCode() != 412) throw e;
    }
  }

  public List<String> keys(String bucket, String prefix) {
    var keys = new ArrayList<String>();
    String token = null;
    do {
      var page =
          s3.listObjectsV2(
              ListObjectsV2Request.builder()
                  .bucket(bucket)
                  .prefix(prefix)
                  .continuationToken(token)
                  .build());
      page.contents().forEach(o -> keys.add(o.key()));
      token = page.nextContinuationToken();
    } while (token != null);
    return keys;
  }

  public String version(String bucket, String key) {
    var head = s3.headObject(HeadObjectRequest.builder().bucket(bucket).key(key).build());
    check(
        head.versionId() != null && !"null".equals(head.versionId()), "Versioned bucket required");
    return head.versionId();
  }

  public String scan(String bucket, String key, String version) {
    var tags =
        s3.getObjectTagging(
            GetObjectTaggingRequest.builder().bucket(bucket).key(key).versionId(version).build());
    return tags.tagSet().stream()
        .filter(t -> t.key().equals("GuardDutyMalwareScanStatus"))
        .map(Tag::value)
        .findFirst()
        .orElse("PENDING");
  }

  /** Missing tags are pending; a completed unsuccessful scan requires operator attention. */
  public boolean isClean(String bucket, String key, String version) {
    String status = scan(bucket, key, version);
    if (CLEAN.equals(status)) return true;
    if ("PENDING".equals(status)) return false;
    throw new IllegalStateException(
        "GuardDuty scan " + status + ": s3://" + bucket + "/" + key + " version=" + version);
  }

  public boolean clean(String bucket, JsonNode ref) {
    String key = required(ref, "key"), version = required(ref, "version");
    if (!isClean(bucket, key, version)) return false;
    check(version.equals(version(bucket, key)), "Object changed after certification: " + key);
    return true;
  }

  public static String planKey(JsonNode plan) {
    return "acquisitions/" + required(plan, "target") + "/" + required(plan, "releaseId") + ".json";
  }

  public static String receiptKey(String id, String dataset, String step) {
    return "control/" + UUID.fromString(id) + "/" + dataset + "/" + step + ".json";
  }

  public JsonNode receipt(String bucket, String id, String dataset, String step) throws Exception {
    return read(bucket, receiptKey(id, dataset, step));
  }

  public void receipt(
      String bucket, String id, String dataset, String step, StreamingS3.Uploaded upload)
      throws Exception {
    immutable(bucket, receiptKey(id, dataset, step), JSON.valueToTree(upload));
  }
}
