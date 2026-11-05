package uk.gov.companieshouse.addresslookup.dbmigrate;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.ArgumentMatchers;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import software.amazon.awssdk.services.ssm.SsmClient;
import software.amazon.awssdk.services.ssm.model.GetParameterRequest;
import software.amazon.awssdk.services.ssm.model.GetParameterResponse;
import software.amazon.awssdk.services.ssm.model.Parameter;
import software.amazon.awssdk.services.ssm.model.ParameterNotFoundException;

import java.util.function.Consumer;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.verify;
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

  @Test
  void returnsTheCredentialsStoredInTheParameter() {
    when(ssmClient.getParameter(ArgumentMatchers.<Consumer<GetParameterRequest.Builder>>any()))
        .thenReturn(parameterResponseWithValue(
            "{\"host\":\"db.local\",\"port\":5432,\"dbname\":\"address_lookup\",\"username\":\"owner\",\"password\":\"pw\"}"));

    var credentials = parameterStoreDbCredentialsSource.get(DB_CREDENTIALS_PARAMETER_PATH);

    assertEquals("jdbc:postgresql://db.local:5432/address_lookup?sslmode=require", credentials.jdbcUrl());
    assertEquals("owner", credentials.username());
    assertEquals("pw", credentials.password());
  }

  @Test
  void requestsTheParameterByPathWithDecryption() {
    when(ssmClient.getParameter(ArgumentMatchers.<Consumer<GetParameterRequest.Builder>>any()))
        .thenReturn(parameterResponseWithValue("{\"jdbcUrl\":\"jdbc:postgresql://h/d?sslmode=require\",\"username\":\"u\",\"password\":\"p\"}"));

    parameterStoreDbCredentialsSource.get(DB_CREDENTIALS_PARAMETER_PATH);

    @SuppressWarnings("unchecked")
    ArgumentCaptor<Consumer<GetParameterRequest.Builder>> requestCustomiserCaptor = ArgumentCaptor.forClass(Consumer.class);
    verify(ssmClient).getParameter(requestCustomiserCaptor.capture());
    var requestBuilder = GetParameterRequest.builder();
    requestCustomiserCaptor.getValue().accept(requestBuilder);
    var request = requestBuilder.build();
    assertEquals(DB_CREDENTIALS_PARAMETER_PATH, request.name());
    assertTrue(request.withDecryption());
  }

  @Test
  void invalidJsonInTheParameterFailsAsCredentialsInvalid() {
    when(ssmClient.getParameter(ArgumentMatchers.<Consumer<GetParameterRequest.Builder>>any()))
        .thenReturn(parameterResponseWithValue("not json"));

    var e =
        assertThrows(
            MigrationException.class,
            () -> parameterStoreDbCredentialsSource.get(DB_CREDENTIALS_PARAMETER_PATH));

    assertEquals(MigrationException.Code.CREDENTIALS_INVALID, e.code());
  }

  private static GetParameterResponse parameterResponseWithValue(String value) {
    return GetParameterResponse.builder().parameter(Parameter.builder().value(value).build()).build();
  }
}

