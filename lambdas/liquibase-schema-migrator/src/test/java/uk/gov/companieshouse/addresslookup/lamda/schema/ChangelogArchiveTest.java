package uk.gov.companieshouse.addresslookup.lamda.schema;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import uk.gov.companieshouse.addresslookup.lamda.schema.MigratorConfiguration.ArchiveLimits;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ChangelogArchiveTest {

    @TempDir
    Path work;

    @Test
    void extractsAChangelogTree() throws IOException {
        byte[] zip = zip(Map.of(
                "db/changelog/db.changelog-master.yaml", "databaseChangeLog: []",
                "db/changelog/changes/creation/001.sql", "--liquibase formatted sql"));

        ChangelogArchive.extract(zip, work, ArchiveLimits.DEFAULT);

        assertEquals("databaseChangeLog: []",
                Files.readString(work.resolve("db/changelog/db.changelog-master.yaml")));
        assertTrue(Files.exists(work.resolve("db/changelog/changes/creation/001.sql")));
    }

    @Test
    void refusesPathTraversal() throws IOException {
        byte[] zip = zip(Map.of("db/../../escaped.sql", "DROP SCHEMA public"));

        assertThrows(IllegalArgumentException.class, () -> ChangelogArchive.extract(zip, work, ArchiveLimits.DEFAULT));
        assertFalse(Files.exists(work.getParent().resolve("escaped.sql")));
    }

    @Test
    void refusesAbsoluteAndWindowsPaths() throws IOException {
        assertThrows(IllegalArgumentException.class,
                () -> ChangelogArchive.extract(zip(Map.of("/etc/x.sql", "")), work, ArchiveLimits.DEFAULT));
        assertThrows(IllegalArgumentException.class,
                () -> ChangelogArchive.extract(zip(Map.of("db\\..\\x.sql", "")), work, ArchiveLimits.DEFAULT));
    }

    @Test
    void refusesFilesThatAreNotChangelogs() throws IOException {
        byte[] zip = zip(Map.of("db/changelog/data/seed.csv", "1,2,3"));

        var e = assertThrows(IllegalArgumentException.class,
                () -> ChangelogArchive.extract(zip, work, ArchiveLimits.DEFAULT));
        assertTrue(e.getMessage().contains("Unexpected file type"));
    }

    @Test
    void refusesAnArchiveThatExpandsTooFar() throws IOException {
        byte[] zip = zip(Map.of("db/big.sql", "x".repeat(10_000)));

        assertThrows(IllegalArgumentException.class,
                () -> ChangelogArchive.extract(zip, work, new ArchiveLimits(1 << 20, 1_000, 10)));
    }

    @Test
    void refusesTooManyEntries() throws IOException {
        Map<String, String> files = new LinkedHashMap<>();
        for (int i = 0; i < 5; i++) {
            files.put("db/" + i + ".sql", "");
        }

        assertThrows(IllegalArgumentException.class,
                () -> ChangelogArchive.extract(zip(files), work, new ArchiveLimits(1 << 20, 1 << 20, 4)));
    }

    @Test
    void refusesADuplicateEntry() throws IOException {
        ByteArrayOutputStream bytes = new ByteArrayOutputStream();
        try (ZipOutputStream zip = new ZipOutputStream(bytes)) {
            // ZipOutputStream itself refuses duplicates, so the second name differs only after normalisation.
            zip.putNextEntry(new ZipEntry("db/a.sql"));
            zip.write("one".getBytes(StandardCharsets.UTF_8));
            zip.putNextEntry(new ZipEntry("db/./a.sql"));
            zip.write("two".getBytes(StandardCharsets.UTF_8));
        }

        assertThrows(IOException.class,
                () -> ChangelogArchive.extract(bytes.toByteArray(), work, ArchiveLimits.DEFAULT));
        assertEquals("one", Files.readString(work.resolve("db/a.sql")));
    }

    @Test
    void verifiesTheReleasedDigest() {
        byte[] archive = "changelog".getBytes(StandardCharsets.UTF_8);
        String digest = HexFormat.of().formatHex(ChangelogArchive.sha256(archive));

        assertDoesNotThrow(() -> ChangelogArchive.verifyDigest(archive, digest, "k"));
        assertThrows(IllegalArgumentException.class,
                () -> ChangelogArchive.verifyDigest(archive, "0".repeat(64), "k"));
    }

    static byte[] zip(Map<String, String> files) throws IOException {
        ByteArrayOutputStream bytes = new ByteArrayOutputStream();
        try (ZipOutputStream zip = new ZipOutputStream(bytes)) {
            for (Map.Entry<String, String> file : files.entrySet()) {
                zip.putNextEntry(new ZipEntry(file.getKey()));
                zip.write(file.getValue().getBytes(StandardCharsets.UTF_8));
                zip.closeEntry();
            }
        }
        return bytes.toByteArray();
    }
}
