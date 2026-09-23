package uk.gov.companieshouse.addresslookup.lamda.shared.storage;

import software.amazon.awssdk.core.sync.RequestBody;
import software.amazon.awssdk.services.s3.S3Client;
import software.amazon.awssdk.services.s3.model.*;

import java.util.Optional;
import java.util.UUID;

import static uk.gov.companieshouse.addresslookup.lamda.shared.runtime.RuntimeSupport.*;

/** A listing position, not processing state. Every retained key is revisited on the next sweep. */
public final class ReconciliationCursor {
  private final S3Client s3;
  private final String bucket;
  private final String prefix;
  private final String cursorKey;

  public ReconciliationCursor(S3Client s3, String bucket, String prefix, String name) {
    this.s3 = s3;
    this.bucket = bucket;
    this.prefix = prefix;
    this.cursorKey = "reconciliation/" + name + ".json";
  }

  /**
   * Advance before doing work so a crash or a permanently bad object cannot starve later keys.
   * Conditional writes prevent concurrent invocations from overwriting each other's position. A
   * crashed attempt is retried on the next sweep; database/file idempotency remains authoritative.
   */
  public Optional<String> next() throws Exception {
    for (int attempt = 0; attempt < 5; attempt++) {
      String after = null, etag = null;
      var present =
          s3.listObjectsV2(
              ListObjectsV2Request.builder().bucket(bucket).prefix(cursorKey).maxKeys(1).build());
      if (present.contents().stream().anyMatch(o -> cursorKey.equals(o.key()))) {
        try (var in =
            s3.getObject(GetObjectRequest.builder().bucket(bucket).key(cursorKey).build())) {
          var bytes = in.readNBytes(4097);
          check(bytes.length <= 4096, "Reconciliation cursor too large");
          after = required(JSON.readTree(bytes), "after");
          check(after.startsWith(prefix), "Unexpected reconciliation cursor prefix");
          etag = in.response().eTag();
        }
      }
      var key = firstAfter(after);
      if (key.isEmpty() && after != null) key = firstAfter(null);
      if (key.isEmpty()) return Optional.empty();
      var request =
          PutObjectRequest.builder().bucket(bucket).key(cursorKey).contentType("application/json");
      if (etag == null) request.ifNoneMatch("*");
      else request.ifMatch(etag);
      // The nonce avoids an identical ETag when a sweep returns to the same key.
      var position =
          JSON.createObjectNode()
              .put("after", key.get())
              .put("claim", UUID.randomUUID().toString());
      try {
        s3.putObject(request.build(), RequestBody.fromString(position.toString()));
        return key;
      } catch (S3Exception conflict) {
        if (conflict.statusCode() != 412 && conflict.statusCode() != 409) throw conflict;
      }
    }
    throw new IllegalStateException("Reconciliation cursor busy; retry next invocation");
  }

  private Optional<String> firstAfter(String key) {
    return s3
        .listObjectsV2(
            ListObjectsV2Request.builder()
                .bucket(bucket)
                .prefix(prefix)
                .startAfter(key)
                .maxKeys(1)
                .build())
        .contents()
        .stream()
        .map(S3Object::key)
        .findFirst();
  }
}
