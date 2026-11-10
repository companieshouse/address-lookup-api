package uk.gov.companieshouse.addresslookup.lambda.shared.util;

import static org.junit.jupiter.api.Assertions.*;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.NullSource;
import org.junit.jupiter.params.provider.ValueSource;
import uk.gov.companieshouse.release.model.Dataset;

class KeyLayoutTest {
  @Test
  void parsesManifestWithoutDataset() {
    String releaseId = "f3d8c9a7-7b4e-4f62-a9d1-8c5e72f1b6a4";
    String key = "control/acquisitions/2026-10-15/" + releaseId + ".json";
    var parsed = KeyLayout.parseObjectKey(key);
    assertEquals(releaseId, parsed.releaseId());
    assertNull(parsed.dataset());
    assertEquals(KeyLayout.FileType.MANIFEST, parsed.fileType());
    assertThrows(IllegalArgumentException.class, () -> KeyLayout.parseZipKey(key));
    assertThrows(IllegalArgumentException.class, () -> KeyLayout.parseCsvKey(key));
  }

  @ParameterizedTest
  @ValueSource(
      strings = {
        "control/acquisitions/../f3d8c9a7-7b4e-4f62-a9d1-8c5e72f1b6a4.json",
        "control/acquisitions/2026-02-30/f3d8c9a7-7b4e-4f62-a9d1-8c5e72f1b6a4.json",
        "control/acquisitions/2026-10-15/not-a-uuid.json",
        "control/acquisitions/2026-10-15/extra/f3d8c9a7-7b4e-4f62-a9d1-8c5e72f1b6a4.json",
        "control/acquisitions/2026-10-15/f3d8c9a7-7b4e-4f62-a9d1-8c5e72f1b6a4.csv"
      })
  void rejectsInvalidManifestKeys(String key) {
    assertThrows(IllegalArgumentException.class, () -> KeyLayout.parseObjectKey(key));
  }

  @Test
  void parsesReceiptsForAllDatasets() {
    String releaseId = "f3d8c9a7-7b4e-4f62-a9d1-8c5e72f1b6a4";
    for (Dataset dataset : Dataset.values()) {
      String key = "control/receipts/" + releaseId + "/" + dataset.datasetName() + "/download.json";
      var parsed = KeyLayout.parseObjectKey(key);
      assertEquals(releaseId, parsed.releaseId());
      assertEquals(dataset, parsed.dataset());
      assertEquals(KeyLayout.FileType.RECEIPT, parsed.fileType());
      assertThrows(IllegalArgumentException.class, () -> KeyLayout.parseZipKey(key));
      assertThrows(IllegalArgumentException.class, () -> KeyLayout.parseCsvKey(key));
    }
  }

  @ParameterizedTest
  @ValueSource(
      strings = {
        "control/receipts/../add_gb_builtaddress/download.json",
        "control/receipts/12345678-1234-1234-1234-123456789abc/extra/add_gb_builtaddress/download.json",
        "control/receipts/12345678-1234-1234-1234-123456789abc/unknown/download.json",
        "control/receipts/not-a-uuid/add_gb_builtaddress/download.json",
        "control/receipts/12345678-1234-1234-1234-123456789abc/add_gb_builtaddress.json"
      })
  void rejectsInvalidReceiptKeys(String key) {
    assertThrows(IllegalArgumentException.class, () -> KeyLayout.parseObjectKey(key));
  }

  @Test
  void parsesScannedZipWithUuidReleaseId() {
    String releaseId = "12345678-1234-1234-1234-123456789abc";
    for (Dataset dataset : Dataset.values()) {
      String key = "landing/raw/" + releaseId + "/" + dataset.datasetName() + ".zip";
      var parsed = KeyLayout.parseObjectKey(key);
      assertEquals(releaseId, parsed.releaseId());
      assertEquals(dataset, parsed.dataset());
      assertEquals(KeyLayout.FileType.ZIP, parsed.fileType());
      assertEquals(key, KeyLayout.zipKey(releaseId, dataset));
      assertEquals(releaseId, KeyLayout.parseZipKey(key).releaseId());
    }
  }

  @ParameterizedTest
  @ValueSource(
      strings = {
        "landing/raw/../add_gb_builtaddress.zip",
        "landing/raw/12345678-1234-1234-1234-123456789abc/extra/add_gb_builtaddress.zip",
        "landing/raw/12345678-1234-1234-1234-123456789abc/unknown.zip",
        "landing/raw/not-a-valid-uuid/add_gb_builtaddress.zip",
        "landing/raw/12345678-1234-1234-1234-123456789abc/add_gb_builtaddress.csv"
      })
  void rejectsInvalidScannedKeys(String key) {
    assertThrows(IllegalArgumentException.class, () -> KeyLayout.parseObjectKey(key));
  }

  @Test
  void parsesReleaseAndDataset() {
    var key = KeyLayout.parseZipKey("landing/raw/20780-131061/add_gb_royalmailaddress.zip");

    assertEquals("20780-131061", key.releaseId());
    assertEquals(Dataset.ADD_GB_ROYALMAILADDRESS, key.dataset());
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
        KeyLayout.parseObjectKey("landing/raw/rel-1/add_gb_royalmailaddress.zip").fileType());
    assertEquals(
        KeyLayout.FileType.CSV,
        KeyLayout.parseObjectKey("landing/extracted/rel-1/add_gb_royalmailaddress.csv").fileType());
  }

  @ParameterizedTest
  @NullSource
  @ValueSource(
      strings = {
        "",
        "landing/raw/rel/unknown_dataset.zip",
        "landing/raw/rel/add_gb_builtaddress.csv",
        "landing/extracted/rel/add_gb_builtaddress.zip",
        "landing/raw/../add_gb_builtaddress.zip",
        "landing/raw//add_gb_builtaddress.zip",
        "landing/raw/rel/extra/add_gb_builtaddress.zip",
        "/zips/rel/add_gb_builtaddress.zip",
        "landing/raw/rel/add_gb_builtaddress.zip/",
        "landing/extracted/rel/add_gb_builtaddress.zip",
        "landing/raw/rel/add_gb_builtaddress.csv",
        "landing/extracted/rel/extra/add_gb_builtaddress.csv"
      })
  void rejectsInvalidKeys(String key) {
    assertThrows(IllegalArgumentException.class, () -> KeyLayout.parseZipKey(key));
  }
}
