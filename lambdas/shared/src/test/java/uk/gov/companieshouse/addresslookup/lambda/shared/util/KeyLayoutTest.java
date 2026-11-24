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
            assertTrue(KeyLayout.isDataPackageManifestKey(manifest));
            assertFalse(KeyLayout.isDataPackageManifestKey(state));
            assertEquals(prefix + "manifest/unpack_state.json", state);
            assertEquals(
                    new KeyLayout.LandingObject(
                            version, null, KeyLayout.LandingFileType.MANIFEST, null),
                    KeyLayout.parseLandingObject(manifest));
            assertEquals(
                    new KeyLayout.LandingObject(
                            version, null, KeyLayout.LandingFileType.UNPACK_STATE, null),
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
                        new KeyLayout.LandingObject(
                                version, dataset, KeyLayout.LandingFileType.ZIP, null),
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
    void supportsMainAndSubTableCsvKeysForAllSixDatasetsAndFeeds() {
        for (var feed : KeyLayout.Feed.values()) {
            var version = new KeyLayout.PackageVersion(feed, "26088", "150450");
            for (var dataset : Dataset.values()) {
                String main =
                        KeyLayout.extractedCsvKey(version, dataset, dataset.datasetName() + ".csv");
                assertTrue(KeyLayout.parseLandingObject(main).isMainCsv());
                assertFalse(
                        KeyLayout.parseLandingObject(KeyLayout.downloadZipKey(version, dataset))
                                .isMainCsv());
                for (String entry :
                        new String[] {dataset.datasetName() + "_altadd.csv", "sub-table_01.csv"}) {
                    String key = KeyLayout.extractedCsvKey(version, dataset, entry);
                    assertEquals(
                            KeyLayout.versionPrefix(version)
                                    + "downloads/"
                                    + dataset.datasetName()
                                    + "/"
                                    + entry,
                            key);
                    var parsed = KeyLayout.parseLandingObject(key);
                    assertEquals(version, parsed.packageVersion());
                    assertEquals(dataset, parsed.dataset());
                    assertEquals(entry, parsed.fileName());
                    assertFalse(parsed.isMainCsv());
                    assertEquals(KeyLayout.FileType.CSV, KeyLayout.parseObjectKey(key).fileType());
                }
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
                "initial_full/package.id/150450/manifest/data_package_manifest.json",
                "initial_full/24833/version.id/manifest/data_package_manifest.json",
                "initial_full//150450/manifest/data_package_manifest.json",
                "initial_full/24833//manifest/data_package_manifest.json",
                "initial_full/../150450/manifest/data_package_manifest.json",
                "initial_full/24833/../manifest/data_package_manifest.json",
                "initial_full/24833/150450/downloads/unknown.zip",
                "initial_full/24833/150450/downloads/add_gb_royalmailaddress/../data.csv",
                "initial_full/24833/150450/downloads/add_gb_royalmailaddress/a..csv",
                "initial_full/24833/150450/downloads/add_gb_royalmailaddress/data.txt",
                "initial_full/24833/150450/downloads/add_gb_royalmailaddress/.csv",
                "initial_full/24833/150450/downloads/add_gb_royalmailaddress/data file.csv",
                "initial_full/24833/150450/downloads/add_gb_royalmailaddress/data\\file.csv",
                "initial_full/24833/150450/downloads/add_gb_royalmailaddress/data.csv/extra",
                "other/24833/150450/manifest/data_package_manifest.json"
            })
    void rejectsInvalidLandingKeys(String key) {
        assertThrows(IllegalArgumentException.class, () -> KeyLayout.parseLandingObject(key));
        assertFalse(KeyLayout.isDataPackageManifestKey(key));
    }

    @Test
    void rejectsInvalidComponentsWhenBuildingKeys() {
        assertThrows(
                IllegalArgumentException.class,
                () ->
                        new KeyLayout.PackageVersion(
                                KeyLayout.Feed.INITIAL_FULL, "../24833", "150450"));
        var version = new KeyLayout.PackageVersion(KeyLayout.Feed.INITIAL_FULL, "24833", "150450");
        assertThrows(
                IllegalArgumentException.class,
                () ->
                        KeyLayout.extractedCsvKey(
                                version, Dataset.ADD_GB_ROYALMAILADDRESS, "../data.csv"));
    }

    @Test
    void scanParserAcceptsKeysProducedForEveryLandingStage() {
        var version = new KeyLayout.PackageVersion(KeyLayout.Feed.MONTHLY_FULL, "12345", "150999");
        Dataset dataset = Dataset.ADD_GB_ROYALMAILADDRESS;

        var manifest = KeyLayout.parseObjectKey(KeyLayout.dataPackageManifestKey(version));
        var zip = KeyLayout.parseObjectKey(KeyLayout.downloadZipKey(version, dataset));
        var csv =
                KeyLayout.parseObjectKey(
                        KeyLayout.extractedCsvKey(
                                version, dataset, dataset.datasetName() + ".csv"));
        var unpackState = KeyLayout.parseObjectKey(KeyLayout.unpackStateKey(version));
        var orderSummary = KeyLayout.parseObjectKey(KeyLayout.orderSummaryKey(version, dataset));

        assertEquals(version, manifest.packageVersion());
        assertEquals(KeyLayout.FileType.MANIFEST, manifest.fileType());
        assertEquals(version, zip.packageVersion());
        assertEquals(dataset, zip.dataset());
        assertEquals(KeyLayout.FileType.ZIP, zip.fileType());
        assertEquals(version, csv.packageVersion());
        assertEquals(dataset, csv.dataset());
        assertEquals(KeyLayout.FileType.CSV, csv.fileType());
        assertEquals(version, unpackState.packageVersion());
        assertEquals(KeyLayout.FileType.UNPACK_STATE, unpackState.fileType());
        assertEquals(version, orderSummary.packageVersion());
        assertEquals(dataset, orderSummary.dataset());
        assertEquals(KeyLayout.FileType.ORDER_SUMMARY, orderSummary.fileType());
    }

    @ParameterizedTest
    @ValueSource(
            strings = {
                "acquisitions/2026-10-15/12345678-1234-1234-1234-123456789abc.json",
                "manifests/2026-10-15/12345678-1234-1234-1234-123456789abc.json",
                "Scanned/12345678-1234-1234-1234-123456789abc/add_gb_royalmailaddress.zip",
                "zips/20780-131061/add_gb_royalmailaddress.zip",
                "csv/20780-131061/add_gb_royalmailaddress.csv",
                "quarantine/csv/12345678-1234-1234-1234-123456789abc/add_gb_royalmailaddress/12345678-1234-1234-1234-123456789abc.csv"
            })
    void rejectsRetiredKeyLayouts(String key) {
        assertThrows(IllegalArgumentException.class, () -> KeyLayout.parseObjectKey(key));
    }
}
