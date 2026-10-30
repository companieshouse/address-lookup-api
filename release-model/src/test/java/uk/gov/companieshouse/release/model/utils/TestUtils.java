package uk.gov.companieshouse.release.model.utils;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

import java.io.BufferedInputStream;
import java.io.IOException;
import java.util.Objects;

public class TestUtils {

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
