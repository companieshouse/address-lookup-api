package uk.gov.companieshouse.release.model;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import java.util.List;
import org.junit.jupiter.api.Test;

class DatasetCatalogTest {
  @Test
  void supportRoyalMailAddressDatasets() {
    assertEquals(
        List.of("add_gb_royalmailaddress", "add_isl_royalmailaddress"),
        DatasetCatalog.tables().stream().map(DatasetCatalog.Table::name).toList());
    assertEquals(2, Dataset.values().length);
    assertThrows(IllegalArgumentException.class, () -> Dataset.named("add_gb_builtaddress"));
    assertThrows(IllegalArgumentException.class, () -> Dataset.named("add_isl_builtaddress"));
  }
}
