package uk.gov.companieshouse.addresslookup.dbmigrate;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import software.amazon.awssdk.services.s3.S3Client;
import software.amazon.awssdk.services.s3.model.NoSuchKeyException;
import software.amazon.awssdk.services.s3.model.S3Exception;

import java.io.InputStream;

import static uk.gov.companieshouse.addresslookup.dbmigrate.MigrationException.Code.BUNDLE_NOT_FOUND;

/** Reads the pinned object version, never "latest". */
public final class S3BundleSource implements BundleSource {
  private static final Logger LOG = LoggerFactory.getLogger(S3BundleSource.class);

  private final S3Client s3;

  public S3BundleSource(S3Client s3) {
    this.s3 = s3;
  }

  @Override
  public InputStream open(String bucket, String key, String versionId) {
    LOG.info("Downloading s3://{}/{} version {}", bucket, key, versionId);
    try {
      return s3.getObject(b -> b.bucket(bucket).key(key).versionId(versionId));
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
}
