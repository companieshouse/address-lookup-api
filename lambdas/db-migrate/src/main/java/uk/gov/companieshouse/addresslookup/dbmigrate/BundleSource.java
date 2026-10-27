package uk.gov.companieshouse.addresslookup.dbmigrate;

import java.io.InputStream;

/** Opens an exact version of a changelog bundle. */
@FunctionalInterface
public interface BundleSource {
  InputStream open(String bucket, String key, String versionId);
}
