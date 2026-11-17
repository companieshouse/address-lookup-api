package uk.gov.companieshouse.addresslookup.lambda.shared.util;

import static org.junit.jupiter.api.Assertions.*;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.NullSource;
import org.junit.jupiter.params.provider.ValueSource;
import uk.gov.companieshouse.release.model.Dataset;

class KeyLayoutTest {
  @Test
  void buildsAndParsesPerVersionLandingKeys() {
    for (var feed : KeyLayout.Feed.values()) {
      var version = new KeyLayout.PackageVersion(feed, "24833", "150450");
      String prefix = feed.name().toLowerCase(java.util.Locale.ROOT) + "/24833/150450/";
      String manifest = KeyLayout.dataPackageManifestKey(version);
      String state = KeyLayout.unpackStateKey(version);
      assertEquals(prefix + "manifest/data_package_manifest.json", manifest);
      assertEquals(prefix + "manifest/unpack_state.json", state);
      assertEquals(
          new KeyLayout.LandingObject(version, null, KeyLayout.LandingFileType.MANIFEST, null),
          KeyLayout.parseLandingObject(manifest));
      assertEquals(
          new KeyLayout.LandingObject(version, null, KeyLayout.LandingFileType.UNPACK_STATE, null),
          KeyLayout.parseLandingObject(state));
      for (Dataset dataset : Dataset.values()) {
        String name = dataset.datasetName();
        String zip = KeyLayout.downloadZipKey(version, dataset);
        String summary = KeyLayout.orderSummaryKey(version, dataset);
        String csv = KeyLayout.extractedCsvKey(version, dataset, name + ".csv");
        assertEquals(prefix + "downloads/" + name + ".zip", zip);
        assertEquals(prefix + "downloads/" + name + "_orderSummary.json", summary);
        assertEquals(prefix + "downloads/" + name + "/" + name + ".csv", csv);
        assertEquals(
            new KeyLayout.LandingObject(version, dataset, KeyLayout.LandingFileType.ZIP, null),
            KeyLayout.parseLandingObject(zip));
        assertEquals(
            new KeyLayout.LandingObject(
                version, dataset, KeyLayout.LandingFileType.ORDER_SUMMARY, null),
            KeyLayout.parseLandingObject(summary));
        assertEquals(
            new KeyLayout.LandingObject(
                version, dataset, KeyLayout.LandingFileType.CSV, name + ".csv"),
            KeyLayout.parseLandingObject(csv));
      }
    }
  }

  @Test
  void buildsPerPackageCursorKeys() {
    assertEquals("cursors/24833.json", KeyLayout.downloadCursorKey("24833"));
    assertEquals("cursors/24833.extraction.json", KeyLayout.extractionCursorKey("24833"));
    assertThrows(IllegalArgumentException.class, () -> KeyLayout.downloadCursorKey("../24833"));
  }

  @ParameterizedTest
  @NullSource
  @ValueSource(
      strings = {
        "",
        "initial_full/../150450/manifest/data_package_manifest.json",
        "initial_full/24833/../manifest/data_package_manifest.json",
        "initial_full/24833/150450/downloads/unknown.zip",
        "initial_full/24833/150450/downloads/add_gb_royalmailaddress/../data.csv",
        "initial_full/24833/150450/downloads/add_gb_royalmailaddress/a..csv",
        "initial_full/24833/150450/downloads/add_gb_royalmailaddress/data.csv/extra",
        "other/24833/150450/manifest/data_package_manifest.json"
      })
  void rejectsInvalidLandingKeys(String key) {
    assertThrows(IllegalArgumentException.class, () -> KeyLayout.parseLandingObject(key));
  }

  @Test
  void rejectsInvalidComponentsWhenBuildingKeys() {
    assertThrows(
        IllegalArgumentException.class,
        () -> new KeyLayout.PackageVersion(KeyLayout.Feed.INITIAL_FULL, "../24833", "150450"));
    var version = new KeyLayout.PackageVersion(KeyLayout.Feed.INITIAL_FULL, "24833", "150450");
    assertThrows(
        IllegalArgumentException.class,
        () -> KeyLayout.extractedCsvKey(version, Dataset.ADD_GB_ROYALMAILADDRESS, "../data.csv"));
  }

  @Test
  void parsesManifestWithoutDataset() {
    String releaseId = "f3d8c9a7-7b4e-4f62-a9d1-8c5e72f1b6a4";
    String key = "acquisitions/2026-10-15/" + releaseId + ".json";
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
        "acquisitions/../f3d8c9a7-7b4e-4f62-a9d1-8c5e72f1b6a4.json",
        "acquisitions/2026-02-30/f3d8c9a7-7b4e-4f62-a9d1-8c5e72f1b6a4.json",
        "acquisitions/2026-10-15/not-a-uuid.json",
        "acquisitions/2026-10-15/extra/f3d8c9a7-7b4e-4f62-a9d1-8c5e72f1b6a4.json",
        "acquisitions/2026-10-15/f3d8c9a7-7b4e-4f62-a9d1-8c5e72f1b6a4.csv"
      })
  void rejectsInvalidManifestKeys(String key) {
    assertThrows(IllegalArgumentException.class, () -> KeyLayout.parseObjectKey(key));
  }

  @Test
  void parsesReceiptsForAllDatasets() {
    String releaseId = "f3d8c9a7-7b4e-4f62-a9d1-8c5e72f1b6a4";
    for (Dataset dataset : Dataset.values()) {
      String key = "Scanned/" + releaseId + "/" + dataset.datasetName() + ".receipt.json";
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
        "Scanned/../add_gb_builtaddress.receipt.json",
        "Scanned/12345678-1234-1234-1234-123456789abc/extra/add_gb_builtaddress.receipt.json",
        "Scanned/12345678-1234-1234-1234-123456789abc/unknown.receipt.json",
        "Scanned/not-a-uuid/add_gb_builtaddress.receipt.json",
        "Scanned/12345678-1234-1234-1234-123456789abc/add_gb_builtaddress.json"
      })
  void rejectsInvalidReceiptKeys(String key) {
    assertThrows(IllegalArgumentException.class, () -> KeyLayout.parseObjectKey(key));
  }

  @Test
  void parsesScannedZipWithUuidReleaseId() {
    String releaseId = "12345678-1234-1234-1234-123456789abc";
    for (Dataset dataset : Dataset.values()) {
      String key = "Scanned/" + releaseId + "/" + dataset.datasetName() + ".zip";
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
        "Scanned/../add_gb_builtaddress.zip",
        "Scanned/12345678-1234-1234-1234-123456789abc/extra/add_gb_builtaddress.zip",
        "Scanned/12345678-1234-1234-1234-123456789abc/unknown.zip",
        "Scanned/not-a-uuid/add_gb_builtaddress.zip",
        "Scanned/12345678-1234-1234-1234-123456789abc/add_gb_builtaddress.csv"
      })
  void rejectsInvalidScannedKeys(String key) {
    assertThrows(IllegalArgumentException.class, () -> KeyLayout.parseObjectKey(key));
  }

  @Test
  void parsesReleaseAndDataset() {
    var key = KeyLayout.parseZipKey("zips/20780-131061/add_gb_royalmailaddress.zip");

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
        KeyLayout.parseObjectKey("zips/rel-1/add_gb_royalmailaddress.zip").fileType());
    assertEquals(
        KeyLayout.FileType.CSV,
        KeyLayout.parseObjectKey("csv/rel-1/add_gb_royalmailaddress.csv").fileType());
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
