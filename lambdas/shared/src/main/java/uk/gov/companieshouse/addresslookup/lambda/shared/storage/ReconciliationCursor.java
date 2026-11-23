package uk.gov.companieshouse.addresslookup.lambda.shared.storage;

import software.amazon.awssdk.core.sync.RequestBody;
import software.amazon.awssdk.services.s3.S3Client;
import software.amazon.awssdk.services.s3.model.*;
import uk.gov.companieshouse.addresslookup.lambda.shared.util.KeyLayout;

import java.io.IOException;
import java.util.Optional;
import java.util.UUID;
import java.util.function.Predicate;

import static uk.gov.companieshouse.addresslookup.lambda.shared.runtime.RuntimeSupport.*;

/** A listing position, not processing state. Every retained key is revisited on the next sweep. */
public final class ReconciliationCursor {
  private final S3Client s3;
  private final String controlBucket;
  private final String landingBucket;
  private final String prefix;
  private final String cursorKey;
  private final Predicate<String> accepts;

  public ReconciliationCursor(
      S3Client s3, String controlBucket, String landingBucket, String prefix, String name) {
    this(s3, controlBucket, landingBucket, prefix, name, key -> true);
  }

  public ReconciliationCursor(
      S3Client s3, String controlBucket, String landingBucket, String prefix, String name,
      Predicate<String> accepts) {
    this.s3 = s3;
    this.controlBucket = controlBucket;
    this.landingBucket = landingBucket;
    this.prefix = prefix;
    this.cursorKey = KeyLayout.reconciliationCursorKey(name);
    this.accepts = accepts;
  }

  /**
   * Advance before doing work so a crash or a permanently bad object cannot starve later keys.
   * Conditional writes prevent concurrent invocations from overwriting each other's position. A
   * crashed attempt is retried on the next sweep; database/file idempotency remains authoritative.
   */
  public Optional<String> next() throws IOException {
    for (int attempt = 0; attempt < 5; attempt++) {
      var position = readPosition();
      var key = firstAfter(position.after());
      if (key.isEmpty() && position.after() != null) key = firstAfter(null);
      if (key.isEmpty()) return Optional.empty();
      if (claim(key.get(), position.etag())) return key;
    }
    throw new IllegalStateException("Reconciliation cursor busy; retry next invocation");
  }

  private Position readPosition() throws IOException {
    var present =
        s3.listObjectsV2(
            ListObjectsV2Request.builder().bucket(controlBucket).prefix(cursorKey).maxKeys(1).build());
    if (present.contents().stream().noneMatch(o -> cursorKey.equals(o.key()))) {
      return new Position(null, null);
    }
    try (var in =
        s3.getObject(GetObjectRequest.builder().bucket(controlBucket).key(cursorKey).build())) {
      var bytes = in.readNBytes(4097);
      check(bytes.length <= 4096, "Reconciliation cursor too large");
      String after = required(JSON.readTree(bytes), "after");
      check(after.startsWith(prefix), "Unexpected reconciliation cursor prefix");
      return new Position(after, in.response().eTag());
    }
  }

  /** Returns false when another invocation advanced the cursor first. */
  private boolean claim(String key, String etag) {
    var request =
        PutObjectRequest.builder().bucket(controlBucket).key(cursorKey).contentType("application/json");
    if (etag == null) request.ifNoneMatch("*");
    else request.ifMatch(etag);
    // The nonce avoids an identical ETag when a sweep returns to the same key.
    var position =
        JSON.createObjectNode().put("after", key).put("claim", UUID.randomUUID().toString());
    try {
      s3.putObject(request.build(), RequestBody.fromString(position.toString()));
      return true;
    } catch (S3Exception conflict) {
      if (conflict.statusCode() != 412 && conflict.statusCode() != 409) throw conflict;
      return false;
    }
  }

  private record Position(String after, String etag) {}

  private Optional<String> firstAfter(String key) {
    String token = null;
    do {
      var page = s3.listObjectsV2(
            ListObjectsV2Request.builder()
                .bucket(landingBucket)
                .prefix(prefix)
                .startAfter(key)
                .continuationToken(token)
                .maxKeys(1000)
                .build());
      var match = page.contents().stream().map(S3Object::key).filter(accepts).findFirst();
      if (match.isPresent()) return match;
      token = page.nextContinuationToken();
    } while (token != null);
    return Optional.empty();
  }
}
