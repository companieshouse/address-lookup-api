package uk.gov.companieshouse.release.model;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.NullSource;
import org.junit.jupiter.params.provider.ValueSource;

import static org.junit.jupiter.api.Assertions.*;

class KeyLayoutTest {
  @Test
  void parsesReleaseAndDataset() {
    var key = KeyLayout.parseZipKey("zips/20780-131061/add_gb_builtaddress.zip");

    assertEquals("20780-131061", key.releaseId());
    assertEquals(Dataset.ADD_GB_BUILTADDRESS, key.dataset());
  }

  @Test
  void acceptsAllFourDatasetsAndRoundTrips() {
    for (Dataset d : Dataset.values()) {
      var parsed = KeyLayout.parseZipKey(KeyLayout.zipKey("rel-1", d));
      assertEquals(d, parsed.dataset());
      assertEquals("rel-1", parsed.releaseId());

      var csv = KeyLayout.parseCsvKey(KeyLayout.csvKey("rel-1", d));
      assertEquals(d, csv.dataset());
      assertEquals("rel-1", csv.releaseId());
      assertEquals(KeyLayout.FileType.CSV, csv.fileType());
    }
  }

  @Test
  void parsesObjectFileType() {
    assertEquals(
        KeyLayout.FileType.ZIP,
        KeyLayout.parseObjectKey("zips/rel-1/add_gb_builtaddress.zip").fileType());
    assertEquals(
        KeyLayout.FileType.CSV,
        KeyLayout.parseObjectKey("csv/rel-1/add_gb_builtaddress.csv").fileType());
  }

  @ParameterizedTest
  @NullSource
  @ValueSource(
      strings = {
        "",
        "zips/rel/unknown_dataset.zip",
        "zips/rel/add_gb_builtaddress.csv",
        "csv/rel/add_gb_builtaddress.zip",
        "zips/../add_gb_builtaddress.zip",
        "zips//add_gb_builtaddress.zip",
        "zips/rel/extra/add_gb_builtaddress.zip",
        "/zips/rel/add_gb_builtaddress.zip",
        "zips/rel/add_gb_builtaddress.zip/",
        "csv/rel/add_gb_builtaddress.zip",
        "zips/rel/add_gb_builtaddress.csv",
        "csv/rel/extra/add_gb_builtaddress.csv"
      })
  void rejectsInvalidKeys(String key) {
    assertThrows(IllegalArgumentException.class, () -> KeyLayout.parseZipKey(key));
  }
}
