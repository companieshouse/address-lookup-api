package uk.gov.companieshouse.addresslookup.lambda.test.utils;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import software.amazon.awssdk.core.ResponseInputStream;
import software.amazon.awssdk.http.AbortableInputStream;
import software.amazon.awssdk.services.s3.model.GetObjectResponse;

import java.io.BufferedInputStream;
import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.util.Objects;

public class TestUtils {
    public static ResponseInputStream<GetObjectResponse> createResponseInputStream(String responseJson) {
        ByteArrayInputStream bis = new ByteArrayInputStream(responseJson.getBytes());
        return  new ResponseInputStream<GetObjectResponse>(
                GetObjectResponse.builder().build(),
                AbortableInputStream.create(bis));
    }

    public static JsonNode jsonFileToJsonNode(String path, ObjectMapper objectMapper) throws Exception {
        return objectMapper.readTree(TestUtils.
                class.getClassLoader().getResourceAsStream(path));
    }

    public static <T> T jsonFileToObject(String path, Class<T> clazz, ObjectMapper objectMapper) throws Exception {
        return objectMapper.readValue(
                TestUtils.class.getClassLoader().getResourceAsStream(path),
                clazz);
    }

    public static String readFileFromClasspath(String path) throws IOException {
        return new String((new BufferedInputStream(Objects.requireNonNull(TestUtils.class.
                getClassLoader().getResourceAsStream(path)))).readAllBytes());
    }
}
