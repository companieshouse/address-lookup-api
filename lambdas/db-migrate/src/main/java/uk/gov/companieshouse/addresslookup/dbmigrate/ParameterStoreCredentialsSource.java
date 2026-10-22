package uk.gov.companieshouse.addresslookup.dbmigrate;

import software.amazon.awssdk.services.ssm.SsmClient;
import software.amazon.awssdk.services.ssm.model.ParameterNotFoundException;

import static uk.gov.companieshouse.addresslookup.dbmigrate.MigrationException.Code.CREDENTIALS_INVALID;

/**
 * Reads the SecureString parameter the pipeline maintains from Vault. Fetched on every
 * invocation, so a rotated password is picked up without a redeploy.
 */
public final class ParameterStoreCredentialsSource implements CredentialsSource {
  private final SsmClient ssm;

  public ParameterStoreCredentialsSource(SsmClient ssm) {
    this.ssm = ssm;
  }

  @Override
  public DatabaseCredentials get(String name) {
    try {
      return DatabaseCredentials.parse(
          ssm.getParameter(b -> b.name(name).withDecryption(true)).parameter().value());
    } catch (ParameterNotFoundException e) {
      throw new MigrationException(CREDENTIALS_INVALID, "Parameter " + name + " does not exist", e);
    }
  }
}
