package uk.gov.companieshouse.addresslookup.dbmigrate;

import software.amazon.awssdk.core.ResponseInputStream;
import software.amazon.awssdk.services.s3.S3Client;
import software.amazon.awssdk.services.s3.model.GetObjectResponse;
import software.amazon.awssdk.services.s3.model.NoSuchKeyException;
import software.amazon.awssdk.services.s3.model.S3Exception;
import uk.gov.companieshouse.logging.Logger;
import uk.gov.companieshouse.logging.LoggerFactory;

import java.io.FilterInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.util.Map;

import static uk.gov.companieshouse.addresslookup.dbmigrate.MigrationException.Code.BUNDLE_NOT_FOUND;

/** Reads the pinned object version, never "latest". */
public final class S3BundleSource implements BundleSource {
  private static final Logger LOG = LoggerFactory.getLogger(S3BundleSource.class.getName());

  private final S3Client s3;

  public S3BundleSource(S3Client s3) {
    this.s3 = s3;
  }

  @Override
  public InputStream open(String bucket, String key, String versionId) {
    LOG.info("Downloading snapshot", Map.of("bucket", bucket, "key", key, "versionId", versionId));
    try {
      return new AbortOnEarlyCloseStream(s3.getObject(b -> b.bucket(bucket).key(key).versionId(versionId)));
    } catch (NoSuchKeyException e) {
      throw new MigrationException(BUNDLE_NOT_FOUND, "s3://" + bucket + "/" + key + " not found", e);
    } catch (S3Exception e) {
      // An unknown versionId is reported as 400/404 depending on bucket state.
      if (e.statusCode() == 400 || e.statusCode() == 404)
        throw new MigrationException(
            BUNDLE_NOT_FOUND,
            "s3://" + bucket + "/" + key + " version " + versionId + " not found",
            e);
      throw e;
    }
  }

  /**
   * Closing a stream before its end would make the HTTP client read the rest of the object.
   * This stream aborts the request instead, so an oversized object is not downloaded in full.
   */
  private static final class AbortOnEarlyCloseStream extends FilterInputStream {
    private final ResponseInputStream<GetObjectResponse> objectResponse;
    private boolean endReached;

    private AbortOnEarlyCloseStream(ResponseInputStream<GetObjectResponse> objectResponse) {
      super(objectResponse);
      this.objectResponse = objectResponse;
    }

    @Override
    public int read() throws IOException {
      int nextByte = super.read();
      if (nextByte == -1) endReached = true;
      return nextByte;
    }

    @Override
    public int read(byte[] buffer, int offset, int length) throws IOException {
      int bytesReadCount = super.read(buffer, offset, length);
      if (bytesReadCount == -1) endReached = true;
      return bytesReadCount;
    }

    @Override
    public void close() throws IOException {
      if (endReached) super.close();
      else objectResponse.abort();
    }
  }
}
