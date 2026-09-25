package uk.gov.companieshouse.addresslookup.lamda.schema;

import software.amazon.awssdk.services.ssm.SsmClient;
import software.amazon.awssdk.services.ssm.model.GetParametersResponse;
import software.amazon.awssdk.services.ssm.model.Parameter;

import java.util.Map;
import java.util.stream.Collectors;

/**
 * Reads the database credentials from Parameter Store on every invocation, so a rotation in Vault reaches the
 * function on the next Terraform apply without a redeploy. Terraform writes the parameters from Vault as
 * SecureStrings under the migrator's own KMS key; the function's role may read only these two.
 */
public final class ParameterStoreCredentials {

    private final SsmClient ssm;
    private final String usernameParameter;
    private final String passwordParameter;

    public ParameterStoreCredentials(SsmClient ssm, String usernameParameter, String passwordParameter) {
        this.ssm = ssm;
        this.usernameParameter = usernameParameter;
        this.passwordParameter = passwordParameter;
    }

    public DatabaseCredentials load() {
        GetParametersResponse response = ssm.getParameters(request -> request
                .names(usernameParameter, passwordParameter)
                .withDecryption(true));

        if (response.hasInvalidParameters() && !response.invalidParameters().isEmpty()) {
            throw new IllegalStateException("Parameter Store has no value for " + response.invalidParameters());
        }

        Map<String, String> values = response.parameters().stream()
                .collect(Collectors.toMap(Parameter::name, Parameter::value, (a, b) -> a));
        return new DatabaseCredentials(values.get(usernameParameter), values.get(passwordParameter));
    }
}
