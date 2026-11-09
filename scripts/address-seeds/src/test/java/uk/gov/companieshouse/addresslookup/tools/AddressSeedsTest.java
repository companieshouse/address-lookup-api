package uk.gov.companieshouse.addresslookup.tools;

import static org.junit.jupiter.api.Assertions.*;

import com.fasterxml.jackson.databind.ObjectMapper;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.util.HexFormat;
import java.util.List;
import java.util.Map;
import org.apache.commons.csv.CSVFormat;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class AddressSeedsTest {
  @TempDir Path temp;
  final ObjectMapper json = new ObjectMapper();

  @Test
  void choosesFullInsteadOfFirstCouAndRejectsAbsentFull() throws Exception {
    var versions =
        json.readTree(
            """
            [{"id":"24833","versions":[
              {"id":"3","supplyType":"COU","createdOn":"2026-11-09","url":"https://api.os.uk/cou"},
              {"id":"2","supplyType":"FULL","createdOn":"2026-10-08","url":"https://api.os.uk/full"}]}]
            """);
    assertEquals("2", AddressSeeds.fullVersion(versions, "24833", "").path("id").asText());
    assertThrows(
        IllegalArgumentException.class, () -> AddressSeeds.fullVersion(versions, "24833", "3"));
    assertThrows(
        IllegalArgumentException.class, () -> AddressSeeds.fullVersion(versions, "missing", ""));
  }

  @Test
  void preservesAllRowsAndQuotedMultilineFieldsWithRealIdsAndGeometry() throws Exception {
    var rows =
        List.of(
            Map.of(
                "udprn",
                "42",
                "postcode",
                "CF10 1FG",
                "organisationname",
                "O'Brien, \"Office\"\nSecond line",
                "geometry",
                "POINT(1 2)"),
            Map.of(
                "udprn",
                "43",
                "postcode",
                "CF10 1FG",
                "organisationname",
                "NULL",
                "geometry",
                "POINT(1 2)"));
    Path csv = csv("gb", rows);
    String sql = AddressSeeds.render(csv, headers(), List.of("CF101FG"), "local-test", false);
    assertTrue(sql.contains("'42'"));
    assertTrue(sql.contains("'43'"));
    assertTrue(sql.contains("O''Brien, \"Office\"\nSecond line"));
    assertTrue(sql.contains("ST_GeomFromText('POINT(1 2)', 27700)"));
    assertTrue(sql.contains("NULL"));
    assertTrue(sql.contains("'NULL'"));
    assertFalse(sql.contains("SYNTHETIC"));
    assertFalse(sql.contains("TEST FIXTURE"));
  }

  @Test
  void missingPostcodeFailsRatherThanPublishingPartialSeeds() throws Exception {
    Path csv =
        csv("gb", List.of(Map.of("udprn", "42", "postcode", "CF10 1FG", "geometry", "POINT(1 2)")));
    var error =
        assertThrows(
            IllegalArgumentException.class,
            () ->
                AddressSeeds.render(
                    csv, headers(), List.of("CF101FG", "CF101PX"), "local-test", false));
    assertTrue(error.getMessage().contains("CF101PX"));
  }

  @Test
  void validatesSchemaAndDuplicateIdentifiers() throws Exception {
    Path bad = temp.resolve("bad.csv");
    Files.writeString(bad, "udprn,postcode\n42,CF10 1FG\n");
    assertThrows(
        IllegalArgumentException.class,
        () -> AddressSeeds.render(bad, headers(), List.of("CF101FG"), "local-test", false));
    Path csv =
        csv(
            "gb",
            List.of(
                Map.of("udprn", "42", "postcode", "CF10 1FG", "geometry", "POINT(1 2)"),
                Map.of("udprn", "42", "postcode", "CF10 1FG", "geometry", "POINT(1 2)")));
    assertThrows(
        IllegalArgumentException.class,
        () -> AddressSeeds.render(csv, headers(), List.of("CF101FG"), "local-test", false));
  }

  @Test
  void islandDataKeepsSourceGeometryAndNullGridCoordinates() throws Exception {
    Path csv =
        csv(
            "isl",
            List.of(Map.of("udprn", "84", "postcode", "BT1 1AA", "geometry", "POINT(-5.9 54.6)")));
    String sql = AddressSeeds.render(csv, headers(), List.of("BT11AA"), "local-test", true);
    assertTrue(sql.contains("os_data.add_isl_royalmailaddress_v2"));
    assertTrue(sql.contains("ST_GeomFromText('POINT(-5.9 54.6)', 4258)"));
    assertFalse(sql.contains("27700"));
  }

  @Test
  void rejectsUnsafeDownloadTargetsAndKeepsKeysOnOsHostOnly() {
    assertThrows(
        IllegalArgumentException.class, () -> AddressSeeds.safeUri("http://api.os.uk/download"));
    assertThrows(
        IllegalArgumentException.class,
        () -> AddressSeeds.safeUri("https://user:password@api.os.uk/download"));
    assertTrue(AddressSeeds.osHost(AddressSeeds.safeUri("https://api.os.uk/download")));
    assertFalse(AddressSeeds.osHost(AddressSeeds.safeUri("https://files.example/download")));
  }

  @Test
  void generationIsAtomicOnMissingPostcodesAndNeverOverwritesExistingFile() throws Exception {
    Path seeds = Files.createDirectory(temp.resolve("seeds"));
    Path source = source();
    assertThrows(
        IllegalArgumentException.class,
        () ->
            AddressSeeds.generate("add", source, seeds, catalog(), List.of("CF101FG", "CF101PX")));
    try (var files = Files.list(seeds)) {
      assertEquals(0, files.count());
    }
    AddressSeeds.generate("add", source, seeds, catalog(), List.of("CF101FG"));
    Path output = seeds.resolve("R__seed_CF101FG.sql");
    String original = Files.readString(output);
    AddressSeeds.generate("add", source, seeds, catalog(), List.of("CF101FG"));
    assertEquals(original, Files.readString(output));
    assertTrue(original.contains("-- Postcodes: CF101FG"));
    assertTrue(original.contains("local-generated-CF101FG"));
    assertTrue(original.contains("FULL version 150450"));
  }

  @Test
  void refreshReplacesLegacySyntheticRowsAndRetainsNoResultPostcodeAbsence() throws Exception {
    Path seeds = Files.createDirectory(temp.resolve("seeds"));
    Path source = source();
    Path output = seeds.resolve("R__seed_synthetic_cardiff_addresses.sql");
    Files.writeString(output, "SELECT 'CF10 1FG', 'CF10 1PX'; -- SYNTHETIC TEST ADDRESS\n");
    AddressSeeds.generate("refresh", source, seeds, catalog(), List.of());
    String result = Files.readString(output);
    assertTrue(
        result.contains(
            "DELETE FROM os_data.add_gb_royalmailaddress_v2 WHERE release_id = 'local-synthetic-cardiff'"));
    assertTrue(result.contains("'42'"));
    assertFalse(result.contains("'CF10 1PX'"));
    assertFalse(result.contains("SYNTHETIC TEST ADDRESS"));
    AddressSeeds.generate("refresh", source, seeds, catalog(), List.of());
    assertEquals(result, Files.readString(output));
  }

  @Test
  void refusesEditedSourceAndFailedArchiveExtractionLeavesExistingCsvUntouched() throws Exception {
    Path seeds = Files.createDirectory(temp.resolve("seeds"));
    Path source = source();
    Files.writeString(source.resolve("add_gb_royalmailaddress.csv"), "edited");
    assertThrows(
        java.io.IOException.class,
        () -> AddressSeeds.generate("add", source, seeds, catalog(), List.of("CF101FG")));
    Path zip = temp.resolve("wrong.zip");
    try (var archive = new java.util.zip.ZipOutputStream(Files.newOutputStream(zip))) {
      archive.putNextEntry(new java.util.zip.ZipEntry("unexpected.csv"));
      archive.write("invalid".getBytes(java.nio.charset.StandardCharsets.UTF_8));
      archive.closeEntry();
    }
    Path target = temp.resolve("existing.csv");
    Files.writeString(target, "keep");
    assertThrows(
        java.io.IOException.class,
        () -> AddressSeeds.extract(zip, "add_gb_royalmailaddress.csv", target));
    assertEquals("keep", Files.readString(target));
  }

  @Test
  void rejectsCorruptPrimaryCrcWithoutReplacingAnExistingCsv() throws Exception {
    Path zip = temp.resolve("corrupt.zip");
    byte[] data = "postcode\nCF10 1FG\n".getBytes(java.nio.charset.StandardCharsets.UTF_8);
    var crc = new java.util.zip.CRC32();
    crc.update(data);
    try (var archive = new java.util.zip.ZipOutputStream(Files.newOutputStream(zip))) {
      var entry = new java.util.zip.ZipEntry("add_gb_royalmailaddress.csv");
      entry.setMethod(java.util.zip.ZipEntry.STORED);
      entry.setSize(data.length);
      entry.setCrc(crc.getValue());
      archive.putNextEntry(entry);
      archive.write(data);
      archive.closeEntry();
    }
    byte[] bytes = Files.readAllBytes(zip);
    int start = 30 + "add_gb_royalmailaddress.csv".length();
    bytes[start] ^= 1;
    Files.write(zip, bytes);
    Path target = temp.resolve("keep.csv");
    Files.writeString(target, "keep");
    assertThrows(
        java.io.IOException.class,
        () -> AddressSeeds.extract(zip, "add_gb_royalmailaddress.csv", target));
    assertEquals("keep", Files.readString(target));
  }

  Path catalog() {
    return Path.of("../../release-model/src/main/resources/catalog.json");
  }

  Path source() throws Exception {
    Path source = Files.createDirectory(temp.resolve("source"));
    Path gb =
        csv(
            "source-gb",
            List.of(Map.of("udprn", "42", "postcode", "CF10 1FG", "geometry", "POINT(1 2)")));
    Path isl =
        csv(
            "source-isl",
            List.of(Map.of("udprn", "84", "postcode", "BT1 1AA", "geometry", "POINT(-5.9 54.6)")));
    var manifest =
        json.createObjectNode()
            .put("supplyType", "FULL")
            .put("packageId", "24833")
            .put("versionId", "150450");
    var files = manifest.putObject("files");
    for (var entry :
        Map.of("add_gb_royalmailaddress", gb, "add_isl_royalmailaddress", isl).entrySet()) {
      Path file = Files.copy(entry.getValue(), source.resolve(entry.getKey() + ".csv"));
      String digest =
          HexFormat.of()
              .formatHex(MessageDigest.getInstance("SHA-256").digest(Files.readAllBytes(file)));
      files.putObject(entry.getKey()).put("csvSha256", digest);
    }
    json.writeValue(source.resolve("source.json").toFile(), manifest);
    return source;
  }

  List<String> headers() throws Exception {
    var catalog = json.readTree(catalog().toFile());
    return json.convertValue(
        catalog.get(0).path("columns").findValues("name"),
        json.getTypeFactory().constructCollectionType(List.class, String.class));
  }

  Path csv(String name, List<Map<String, String>> records) throws Exception {
    Path path = temp.resolve(name + ".csv");
    var headers = headers();
    try (var writer = Files.newBufferedWriter(path);
        var printer =
            CSVFormat.DEFAULT
                .builder()
                .setHeader(headers.toArray(String[]::new))
                .get()
                .print(writer)) {
      for (var record : records) {
        printer.printRecord(headers.stream().map(h -> record.getOrDefault(h, "")).toList());
      }
    }
    return path;
  }
}
