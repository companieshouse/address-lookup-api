package uk.gov.companieshouse.addresslookup.dbmigrate;

/** Resolves the schema-owner database credentials. */
@FunctionalInterface
public interface CredentialsSource {
  DatabaseCredentials get(String name);
}
