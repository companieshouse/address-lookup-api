package uk.gov.companieshouse.addresslookup.dbmigrate;

import static uk.gov.companieshouse.addresslookup.dbmigrate.MigrationException.Code.CREDENTIALS_INVALID;

import java.util.Map;
import software.amazon.awssdk.services.ssm.SsmClient;
import software.amazon.awssdk.services.ssm.model.ParameterNotFoundException;
import uk.gov.companieshouse.logging.Logger;
import uk.gov.companieshouse.logging.LoggerFactory;

/**
 * Reads the SecureString parameter the pipeline maintains from Vault. Fetched on every
 * invocation, so a rotated password is picked up without a redeploy.
 */
public final class ParameterStoreDbCredentialsSource implements DbCredentialsSource {
  private static final Logger LOG =
      LoggerFactory.getLogger(ParameterStoreDbCredentialsSource.class.getName());

  private final SsmClient ssm;

  public ParameterStoreDbCredentialsSource(SsmClient ssm) {
    this.ssm = ssm;
  }

  @Override
  public DatabaseCredentials get(String dbCredentialsParameterPath) {
    // Only the parameter path is logged, never its value.
    LOG.info("Reading database credentials", Map.of("parameter", dbCredentialsParameterPath));
    try {
      return DatabaseCredentials.fromJson(
          ssm.getParameter(b -> b.name(dbCredentialsParameterPath).withDecryption(true))
              .parameter()
              .value());
    } catch (ParameterNotFoundException e) {
      throw new MigrationException(
          CREDENTIALS_INVALID, "Parameter " + dbCredentialsParameterPath + " does not exist", e);
    }
  }
}
