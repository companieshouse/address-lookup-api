package uk.gov.companieshouse.release.model;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;

import java.util.List;

class DatasetCatalogTest {
    @Test
    void supportRoyalMailAddressDatasets() {
        assertEquals(
                List.of("add_gb_royalmailaddress", "add_isl_royalmailaddress"),
                DatasetCatalog.tables().stream().map(DatasetCatalog.Table::name).toList());
        assertEquals(6, Dataset.values().length);
        assertEquals(
                List.of(Dataset.ADD_GB_ROYALMAILADDRESS, Dataset.ADD_ISL_ROYALMAILADDRESS),
                DatasetCatalog.datasets());
        assertTrue(DatasetCatalog.datasets().stream().allMatch(Dataset::supportsDatabaseImport));
    }

    @ParameterizedTest
    @EnumSource(
            value = Dataset.class,
            names = {
                "ADD_GB_BUILTADDRESS", "ADD_GB_PREBUILDADDRESS",
                "ADD_ISL_BUILTADDRESS", "ADD_ISL_PREBUILDADDRESS"
            })
    void keyOnlyDatasetsHaveNoImportSchemaOrDatabaseMapping(Dataset dataset) {
        assertFalse(dataset.supportsDatabaseImport());
        assertEquals(dataset, Dataset.named(dataset.datasetName()));
        assertThrows(IllegalArgumentException.class, () -> DatasetCatalog.table(dataset));
        assertThrows(IllegalStateException.class, () -> dataset.landing(SupplyType.FULL));
        assertThrows(IllegalStateException.class, () -> dataset.prepareCall(SupplyType.COU));
    }
}
