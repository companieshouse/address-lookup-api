package uk.gov.companieshouse.addresslookup.lambda.shared.storage;

import java.util.Map;

/** Bucket and key of the object being written. */
record ObjectTarget(String bucket, String key) {

  Map<String, Object> logFields() {
    return Map.of(StreamingS3.BUCKET_FIELD, bucket, StreamingS3.KEY_FIELD, key);
  }
}
