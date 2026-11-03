package uk.gov.companieshouse.release.model;

import static org.junit.jupiter.api.Assertions.assertEquals;
import org.junit.jupiter.api.Test;

class DatasetDefaultTest {

    @Test
    void testDatasetDefault() {
        DatasetDefault datasetDefault = new DatasetDefault("NAME", "VERSION");
        assertEquals("NAME", datasetDefault.name());
        assertEquals("VERSION", datasetDefault.version());
    }
}
