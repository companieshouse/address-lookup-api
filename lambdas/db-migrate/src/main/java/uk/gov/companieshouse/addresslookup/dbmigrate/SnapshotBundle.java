package uk.gov.companieshouse.addresslookup.dbmigrate;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.DigestInputStream;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;
import java.util.regex.Pattern;
import java.util.zip.ZipEntry;
import java.util.zip.ZipException;
import java.util.zip.ZipFile;

import static uk.gov.companieshouse.addresslookup.dbmigrate.MigrationException.Code.BUNDLE_INVALID;
import static uk.gov.companieshouse.addresslookup.dbmigrate.MigrationException.Code.BUNDLE_TOO_LARGE;
import static uk.gov.companieshouse.addresslookup.dbmigrate.MigrationException.Code.MIGRATIONS_NOT_FOUND;
import static uk.gov.companieshouse.addresslookup.dbmigrate.MigrationException.Code.VALIDATION_FAILED;

/**
 * Copies a Flyway snapshot zip to local disk and extracts it defensively. A snapshot holds
 * {@code migrations/**} and nothing else, so seed data can never reach a deployed database.
 */
public final class SnapshotBundle {
  static final String MIGRATIONS = "migrations";
  /** Versioned ({@code V2026.001__desc.sql}) or repeatable ({@code R__desc.sql}). */
  private static final Pattern MIGRATION_NAME =
      Pattern.compile("(V[0-9]+(\\.[0-9]+)*|R)__[A-Za-z0-9_]+\\.sql");

  private SnapshotBundle() {}

  /** Result of {@link #extract}: where the migrations are, and what was extracted. */
  public record Extracted(Path root, String sha256, long zipBytes, int files) {
    public Path migrations() {
      return root.resolve(MIGRATIONS);
    }
  }

  /**
   * Streams {@code zip} to {@code workDir/snapshot.zip} (bounded by {@code maxBytes}), then
   * extracts it into {@code workDir/root}. The same {@code maxBytes} bounds the total expanded size.
   */
  public static Extracted extract(InputStream zip, Path workDir, long maxBytes, int maxEntries)
      throws IOException {
    Path zipFile = workDir.resolve("snapshot.zip");
    MessageDigest sha = sha256();
    long zipBytes = copyBounded(new DigestInputStream(zip, sha), zipFile, maxBytes);
    Path root = Files.createDirectories(workDir.resolve("root")).toRealPath();

    int files = 0;
    long expanded = 0;
    try (ZipFile archive = new ZipFile(zipFile.toFile())) {
      if (archive.size() > maxEntries)
        throw new MigrationException(BUNDLE_TOO_LARGE, "Snapshot has more than " + maxEntries + " entries");
      for (var entries = archive.entries(); entries.hasMoreElements(); ) {
        ZipEntry entry = entries.nextElement();
        Path target = safeTarget(root, entry.getName());
        if (!target.startsWith(root.resolve(MIGRATIONS)))
          throw new MigrationException(
              BUNDLE_INVALID, "Snapshot entry outside " + MIGRATIONS + "/: " + entry.getName());
        if (entry.isDirectory()) {
          Files.createDirectories(target);
          continue;
        }
        // Flyway would otherwise skip a misnamed file or fail with a generic error mid-run.
        String fileName = target.getFileName().toString();
        if (!target.getParent().equals(root.resolve(MIGRATIONS))
            || !MIGRATION_NAME.matcher(fileName).matches())
          throw new MigrationException(
              VALIDATION_FAILED,
              "Misnamed migration " + entry.getName()
                  + ": expected migrations/V<version>__<description>.sql or migrations/R__<description>.sql");
        Files.createDirectories(target.getParent());
        try (InputStream in = archive.getInputStream(entry)) {
          expanded += copyBounded(in, target, maxBytes - expanded);
        }
        files++;
      }
    } catch (ZipException e) {
      throw new MigrationException(BUNDLE_INVALID, "Snapshot is not a valid zip", e);
    }
    if (files == 0)
      throw new MigrationException(MIGRATIONS_NOT_FOUND, "Snapshot contains no migrations");
    return new Extracted(root, HexFormat.of().formatHex(sha.digest()), zipBytes, files);
  }

  static Path safeTarget(Path root, String name) {
    if (name.isEmpty()
        || name.startsWith("/")
        || name.startsWith("\\")
        || name.contains("\\")
        || name.contains(":")
        || name.contains("\0"))
      throw new MigrationException(BUNDLE_INVALID, "Unsafe snapshot entry: " + name);
    for (String segment : name.split("/"))
      if (segment.equals("..") || segment.equals("."))
        throw new MigrationException(BUNDLE_INVALID, "Unsafe snapshot entry: " + name);
    Path target = root.resolve(name).normalize();
    if (!target.startsWith(root))
      throw new MigrationException(BUNDLE_INVALID, "Unsafe snapshot entry: " + name);
    return target;
  }

  private static long copyBounded(InputStream in, Path target, long limit) throws IOException {
    long total = 0;
    byte[] buffer = new byte[64 * 1024];
    try (OutputStream out = Files.newOutputStream(target)) {
      int n;
      while ((n = in.read(buffer)) != -1) {
        total += n;
        if (total > limit)
          throw new MigrationException(BUNDLE_TOO_LARGE, "Snapshot exceeds " + limit + " bytes");
        out.write(buffer, 0, n);
      }
    }
    return total;
  }

  private static MessageDigest sha256() {
    try {
      return MessageDigest.getInstance("SHA-256");
    } catch (NoSuchAlgorithmException e) {
      throw new IllegalStateException(e);
    }
  }
}
