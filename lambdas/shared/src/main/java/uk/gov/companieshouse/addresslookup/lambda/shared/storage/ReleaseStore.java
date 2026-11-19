package uk.gov.companieshouse.addresslookup.lambda.shared.storage;

import static uk.gov.companieshouse.addresslookup.lambda.shared.runtime.RuntimeSupport.*;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import java.io.IOException;
import java.net.HttpURLConnection;
import java.util.ArrayList;
import java.util.List;
import java.util.function.UnaryOperator;
import software.amazon.awssdk.core.sync.RequestBody;
import software.amazon.awssdk.services.s3.S3Client;
import software.amazon.awssdk.services.s3.model.*;

/** S3 progress records and scan evidence; no database dependency. */
public final class ReleaseStore {
  public static final String CLEAN = "NO_THREATS_FOUND";
  private static final String JSON_CONTENT_TYPE = "application/json";
  private static final String FIELD_S3_KEY = "s3Key";
  private static final String FIELD_VERSION = "version";
  private static final int MAX_STATE_UPDATE_ATTEMPTS = 5;
  private final S3Client s3;

  public ReleaseStore(S3Client s3) {
    this.s3 = s3;
  }

  public JsonNode read(String bucket, String key) throws IOException {
    // Prefix-scoped ListBucket grants need not reveal missing keys through GET's 404/403
    // distinction.
    // Check the exact permitted key before reading optional state records.
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

  public void immutable(String bucket, String key, JsonNode value) {
    try {
      s3.putObject(
          PutObjectRequest.builder()
              .bucket(bucket)
              .key(key)
              .ifNoneMatch("*")
              .contentType(JSON_CONTENT_TYPE)
              .build(),
          RequestBody.fromString(value.toString()));
    } catch (S3Exception e) {
      if (!isPreconditionFailed(e)) throw e;
    }
  }

  /** Stores mutable operational state. Immutable release descriptions must use {@link #immutable}. */
  public void write(String bucket, String key, JsonNode value) {
    s3.putObject(
        PutObjectRequest.builder().bucket(bucket).key(key).contentType(JSON_CONTENT_TYPE).build(),
        RequestBody.fromString(value.toString()));
  }

  public ObjectNode updateState(
      String bucket, String key, UnaryOperator<ObjectNode> update) throws IOException {
    for (int attempt = 0; attempt < MAX_STATE_UPDATE_ATTEMPTS; attempt++) {
      ObjectNode current = JSON.createObjectNode();
      String eTag = null;
      if (exists(bucket, key)) {
        try (var in = s3.getObject(GetObjectRequest.builder().bucket(bucket).key(key).build())) {
          byte[] bytes = in.readNBytes(1024 * 1024 + 1);
          check(bytes.length <= 1024 * 1024, "State exceeds 1 MiB");
          JsonNode parsed = JSON.readTree(bytes);
          check(parsed != null && parsed.isObject(), "State must be a JSON object");
          current = (ObjectNode) parsed;
          eTag = in.response().eTag();
          check(eTag != null, "State object is missing an ETag");
        } catch (S3Exception e) {
          if (e.statusCode() != 404) throw e;
          continue;
        }
      }

      ObjectNode updated = update.apply(current.deepCopy());
      check(updated != null, "State update returned no value");
      var request = PutObjectRequest.builder().bucket(bucket).key(key).contentType(JSON_CONTENT_TYPE);
      if (eTag == null) request.ifNoneMatch("*");
      else request.ifMatch(eTag);
      try {
        s3.putObject(request.build(), RequestBody.fromString(updated.toString()));
        return updated;
      } catch (S3Exception e) {
        if (e.statusCode() != 409 && !isPreconditionFailed(e)) throw e;
      }
    }
    throw new IllegalStateException("State update contention; retry invocation");
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

  public boolean exists(String bucket, String key) {
    var result =
        s3.listObjectsV2(
            ListObjectsV2Request.builder().bucket(bucket).prefix(key).maxKeys(1).build());
    return result.contents().stream().anyMatch(object -> key.equals(object.key()));
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

  public boolean isReferenceClean(String bucket, JsonNode ref) {
    String key = ref.hasNonNull(FIELD_S3_KEY) ? required(ref, FIELD_S3_KEY) : required(ref, "key");
    String version =
        ref.hasNonNull(FIELD_VERSION) ? required(ref, FIELD_VERSION) : version(bucket, key);
    if (!isClean(bucket, key, version)) return false;
    check(version.equals(version(bucket, key)), "Object changed after certification: " + key);
    return true;
  }

  public static String objectKey(JsonNode ref) {
    return ref.hasNonNull(FIELD_S3_KEY) ? required(ref, FIELD_S3_KEY) : required(ref, "key");
  }

  public static String objectVersion(ReleaseStore store, String bucket, JsonNode ref) {
    return ref.hasNonNull(FIELD_VERSION)
        ? required(ref, FIELD_VERSION)
        : store.version(bucket, objectKey(ref));
  }

  public static boolean isPreconditionFailed(S3Exception e) {
    return e.statusCode() == HttpURLConnection.HTTP_PRECON_FAILED;
  }
}
