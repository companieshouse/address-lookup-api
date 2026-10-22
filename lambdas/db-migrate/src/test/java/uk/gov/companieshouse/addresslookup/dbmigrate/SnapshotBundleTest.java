package uk.gov.companieshouse.addresslookup.dbmigrate;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

class SnapshotBundleTest {

  @TempDir Path work;

  static byte[] zip(Map<String, String> entries) throws IOException {
    var bytes = new ByteArrayOutputStream();
    try (var zip = new ZipOutputStream(bytes)) {
      for (var e : entries.entrySet()) {
        zip.putNextEntry(new ZipEntry(e.getKey()));
        if (e.getValue() != null) zip.write(e.getValue().getBytes(StandardCharsets.UTF_8));
        zip.closeEntry();
      }
    }
    return bytes.toByteArray();
  }

  private MigrationException.Code failure(byte[] zip, long maxBytes, int maxEntries) {
    return assertThrows(
            MigrationException.class,
            () -> SnapshotBundle.extract(new ByteArrayInputStream(zip), work, maxBytes, maxEntries))
        .code();
  }

  @Test
  void extractsMigrationsAndReportsChecksum() throws Exception {
    var entries = new LinkedHashMap<String, String>();
    entries.put("migrations/", null);
    entries.put("migrations/V2026.001__create_schemas.sql", "CREATE SCHEMA os_data;\n");
    entries.put("migrations/V2026.002__create_tables.sql", "SELECT 1;\n");
    byte[] zip = zip(entries);

    var extracted = SnapshotBundle.extract(new ByteArrayInputStream(zip), work, 1_000_000, 100);

    assertEquals(2, extracted.files());
    assertEquals(zip.length, extracted.zipBytes());
    assertEquals(
        HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(zip)), extracted.sha256());
    assertEquals(
        "CREATE SCHEMA os_data;\n",
        Files.readString(extracted.migrations().resolve("V2026.001__create_schemas.sql")));
  }

  @Test
  void rejectsAnythingOutsideMigrations() throws Exception {
    for (String name : new String[] {"seed/R__seed.sql", "V1__top_level.sql", "migrationsx/V1__x.sql"})
      assertEquals(
          MigrationException.Code.BUNDLE_INVALID,
          failure(zip(Map.of("migrations/V1__ok.sql", "x", name, "x")), 1_000_000, 100),
          name);
  }

  @Test
  void rejectsMisnamedMigrations() throws Exception {
    for (String name :
        new String[] {
          "migrations/V2_missing_separator.sql", "migrations/v1__lower_case.sql", "migrations/V1__x.txt",
          "migrations/V1.a__bad_version.sql", "migrations/sub/V1__nested.sql", "migrations/U1__undo.sql"
        })
      assertEquals(
          MigrationException.Code.VALIDATION_FAILED,
          failure(zip(Map.of("migrations/V1__ok.sql", "x", name, "x")), 1_000_000, 100),
          name);
    var ok = Map.of("migrations/V2026.001__a.sql", "x", "migrations/R__refresh_view.sql", "x");
    assertEquals(2, SnapshotBundle.extract(new ByteArrayInputStream(zip(ok)), work, 1_000_000, 100).files());
  }

  @Test
  void rejectsPathTraversalAndAbsolutePaths() throws Exception {
    for (String name :
        new String[] {"../evil.sql", "migrations/../../evil.sql", "/etc/cron.d/x", "C:\\x", "a\\..\\b"})
      assertEquals(MigrationException.Code.BUNDLE_INVALID, failure(zip(Map.of(name, "x")), 1_000_000, 100), name);
  }

  @Test
  void enforcesSizeAndEntryLimits() throws Exception {
    byte[] big = zip(Map.of("migrations/V1__big.sql", "x".repeat(10_000)));
    assertEquals(MigrationException.Code.BUNDLE_TOO_LARGE, failure(big, 5_000, 100));

    var many = new LinkedHashMap<String, String>();
    for (int i = 0; i < 5; i++) many.put("migrations/V" + (i + 1) + "__f.sql", "x");
    assertEquals(MigrationException.Code.BUNDLE_TOO_LARGE, failure(zip(many), 1_000_000, 4));
  }

  @Test
  void rejectsNonZipAndSnapshotsWithoutMigrations() throws Exception {
    assertEquals(
        MigrationException.Code.BUNDLE_INVALID,
        failure("not a zip".getBytes(StandardCharsets.UTF_8), 1_000_000, 100));
    assertEquals(
        MigrationException.Code.MIGRATIONS_NOT_FOUND,
        failure(zip(Map.of("migrations/", "")), 1_000_000, 100));
  }
}
