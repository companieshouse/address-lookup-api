package uk.gov.companieshouse.addresslookup.dbbootstrap;

import software.amazon.awssdk.services.ssm.SsmClient;

import java.util.function.Function;

/**
 * Reads a credentials SecureString on every invocation, so a rotation in Vault reaches the function on the next
 * Terraform apply without a redeploy. The function's role may read only the master and schema-owner parameters.
 */
public final class ParameterStoreCredentials implements Function<String, DatabaseCredentials> {

    private final SsmClient ssm;

    public ParameterStoreCredentials(SsmClient ssm) {
        this.ssm = ssm;
    }

    @Override
    public DatabaseCredentials apply(String parameterName) {
        String value = ssm.getParameter(request -> request.name(parameterName).withDecryption(true))
                .parameter()
                .value();
        try {
            return DatabaseCredentials.fromJson(value);
        } catch (IllegalArgumentException e) {
            throw new IllegalStateException(parameterName + ": " + e.getMessage(), e);
        }
    }
}
