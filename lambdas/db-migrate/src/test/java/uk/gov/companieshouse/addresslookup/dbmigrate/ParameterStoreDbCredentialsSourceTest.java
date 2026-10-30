package uk.gov.companieshouse.addresslookup.dbmigrate;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentMatchers;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import software.amazon.awssdk.services.ssm.SsmClient;
import software.amazon.awssdk.services.ssm.model.GetParameterRequest;
import software.amazon.awssdk.services.ssm.model.ParameterNotFoundException;

import java.util.function.Consumer;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class ParameterStoreDbCredentialsSourceTest {

  private static final String DB_CREDENTIALS_PARAMETER_PATH = "/address-lookup/test/db/schema-owner";

  @Mock private SsmClient ssmClient;

  private ParameterStoreDbCredentialsSource parameterStoreDbCredentialsSource;

  @BeforeEach
  void setup() {
    parameterStoreDbCredentialsSource = new ParameterStoreDbCredentialsSource(ssmClient);
  }

  @Test
  void missingParameterFailsAsCredentialsInvalid() {
    when(ssmClient.getParameter(ArgumentMatchers.<Consumer<GetParameterRequest.Builder>>any()))
        .thenThrow(ParameterNotFoundException.builder().build());

    var e =
        assertThrows(
            MigrationException.class,
            () -> parameterStoreDbCredentialsSource.get(DB_CREDENTIALS_PARAMETER_PATH));

    assertEquals(MigrationException.Code.CREDENTIALS_INVALID, e.code());
    assertEquals(
        "CREDENTIALS_INVALID: Parameter /address-lookup/test/db/schema-owner does not exist",
        e.getMessage());
  }
}

