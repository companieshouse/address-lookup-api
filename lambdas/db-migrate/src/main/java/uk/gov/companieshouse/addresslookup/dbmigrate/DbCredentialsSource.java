package uk.gov.companieshouse.addresslookup.dbmigrate;

/** Resolves the schema-owner database credentials. */
@FunctionalInterface
public interface DbCredentialsSource {
  /** @param dbCredentialsParameterPath the full path of the parameter, e.g. {@code /<prefix>/schema-owner} */
  DatabaseCredentials get(String dbCredentialsParameterPath);
}
