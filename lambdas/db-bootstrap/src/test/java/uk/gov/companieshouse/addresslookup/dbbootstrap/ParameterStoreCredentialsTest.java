package uk.gov.companieshouse.addresslookup.dbbootstrap;

import org.junit.jupiter.api.Test;
import software.amazon.awssdk.services.ssm.SsmClient;
import software.amazon.awssdk.services.ssm.model.GetParameterRequest;
import software.amazon.awssdk.services.ssm.model.GetParameterResponse;
import software.amazon.awssdk.services.ssm.model.Parameter;

import java.util.function.Consumer;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class ParameterStoreCredentialsTest {

    private final SsmClient ssm = mock(SsmClient.class);

    @Test
    @SuppressWarnings("unchecked")
    void readsAndDecryptsTheParameter() {
        GetParameterRequest[] sent = new GetParameterRequest[1];
        when(ssm.getParameter(any(Consumer.class))).thenAnswer(call -> {
            GetParameterRequest.Builder builder = GetParameterRequest.builder();
            ((Consumer<GetParameterRequest.Builder>) call.getArgument(0)).accept(builder);
            sent[0] = builder.build();
            return response("{\"jdbcUrl\":\"jdbc:postgresql://writer:5432/addressdb\",\"username\":\"owner\",\"password\":\"pw\"}");
        });

        DatabaseCredentials credentials = new ParameterStoreCredentials(ssm).apply("/address-lookup/cidev/db/schema-owner");

        assertEquals("owner", credentials.username());
        assertEquals("/address-lookup/cidev/db/schema-owner", sent[0].name());
        assertTrue(sent[0].withDecryption());
    }

    @Test
    @SuppressWarnings("unchecked")
    void namesTheParameterButNotTheValueOnError() {
        when(ssm.getParameter(any(Consumer.class))).thenReturn(response("{\"password\":\"s3cret\"}"));

        IllegalStateException e = assertThrows(IllegalStateException.class,
                () -> new ParameterStoreCredentials(ssm).apply("/address-lookup/cidev/db/master"));

        assertTrue(e.getMessage().startsWith("/address-lookup/cidev/db/master"));
        assertFalse(e.getMessage().contains("s3cret"));
    }

    private static GetParameterResponse response(String value) {
        return GetParameterResponse.builder().parameter(Parameter.builder().value(value).build()).build();
    }
}
