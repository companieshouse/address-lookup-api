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
 * Copies a Flyway snapshot zip to local disk, calculates its SHA-256 checksum and extracts it defensively.
 * A snapshot holds {@code migrations/**} and nothing else, so seed data can never reach a deployed database.
 */
public final class SnapshotZipExtractor {
  /** Name of the only top-level directory allowed inside the snapshot zip. */
  static final String MIGRATIONS_DIR_NAME = "migrations";
  /** Allowed migration SQL file names: versioned ({@code V2026.001__desc.sql}) or repeatable ({@code R__desc.sql}). */
  private static final Pattern SQL_FILE_NAME_PATTERN =
      Pattern.compile("(V[0-9]+(\\.[0-9]+)*|R)__[A-Za-z0-9_]+\\.sql");
  private static final int COPY_BUFFER_BYTES = 64 * 1024;

  private SnapshotZipExtractor() {}

  /**
   * Result of {@link #copyAndExtract}.
   *
   * @param extractedRootDir extracted root directory, which contains {@code migrations/}
   * @param zipFileSha256 SHA-256 checksum of the copied zip file, in lower-case hexadecimal
   * @param copiedZipFileBytes size of the copied zip file in bytes
   * @param sqlFileCount number of extracted migration SQL files
   */
  public record ExtractedSnapshot(
      Path extractedRootDir, String zipFileSha256, long copiedZipFileBytes, int sqlFileCount) {
    public Path migrations() {
      return extractedRootDir.resolve(MIGRATIONS_DIR_NAME);
    }
  }

  /**
   * Streams {@code inputStreamZipFile} to {@code tempWorkDir/snapshot.zip} (bounded by {@code maxZipFileBytes}),
   * then extracts it into {@code tempWorkDir/root}. The same {@code maxZipFileBytes} also bounds the total
   * expanded size, and {@code maxZipFileEntries} bounds the number of zip entries.
   *
   * @throws MigrationException if the zip is invalid, too large, unsafe, misnamed or contains no migrations
   */
  public static ExtractedSnapshot copyAndExtract(
      InputStream inputStreamZipFile, Path tempWorkDir, long maxZipFileBytes, int maxZipFileEntries)
      throws IOException {
    Path tempZipFilePath = tempWorkDir.resolve("snapshot.zip");
    MessageDigest sha256Digest = sha256();
    long copiedZipFileBytes =
        copyWithSizeLimit(new DigestInputStream(inputStreamZipFile, sha256Digest), tempZipFilePath, maxZipFileBytes);
    Path extractedRootDir = Files.createDirectories(tempWorkDir.resolve("root")).toRealPath();

    int extractedSqlFileCount =
        extractZipFileEntries(tempZipFilePath, extractedRootDir, maxZipFileBytes, maxZipFileEntries);

    validateAtLeastOneMigrationSqlFile(extractedSqlFileCount);
    return new ExtractedSnapshot(
        extractedRootDir, HexFormat.of().formatHex(sha256Digest.digest()), copiedZipFileBytes, extractedSqlFileCount);
  }

  /** Validates each zip entry before writing it under {@code extractedRootDir}. Returns the number of SQL files written. */
  private static int extractZipFileEntries(
      Path tempZipFilePath, Path extractedRootDir, long maxZipFileBytes, int maxZipFileEntries) throws IOException {
    Path migrationsDir = extractedRootDir.resolve(MIGRATIONS_DIR_NAME);
    int extractedSqlFileCount = 0;
    long totalExpandedByteCount = 0;
    try (ZipFile zipArchive = new ZipFile(tempZipFilePath.toFile())) {
      validateEntryCount(zipArchive, maxZipFileEntries);
      for (var zipEntries = zipArchive.entries(); zipEntries.hasMoreElements(); ) {
        ZipEntry zipEntry = zipEntries.nextElement();

        Path targetPath = validateTargetPath(extractedRootDir, zipEntry.getName());
        validateInsideMigrationsDir(targetPath, migrationsDir, zipEntry);
        if (zipEntry.isDirectory()) {
          Files.createDirectories(targetPath);
          continue;
        }
        validateMigrationSqlFileName(targetPath, migrationsDir, zipEntry);

        totalExpandedByteCount += writeEntry(zipArchive, zipEntry, targetPath, maxZipFileBytes - totalExpandedByteCount);
        extractedSqlFileCount++;
      }
    } catch (ZipException e) {
      throw new MigrationException(BUNDLE_INVALID, "Snapshot is not a valid zip", e);
    }
    return extractedSqlFileCount;
  }

  /** Writes one zip entry to disk, limited to {@code remainingByteAllowance}, and returns the number of bytes written. */
  private static long writeEntry(ZipFile zipArchive, ZipEntry zipEntry, Path targetPath, long remainingByteAllowance)
      throws IOException {
    Files.createDirectories(targetPath.getParent());
    try (InputStream zipEntryStream = zipArchive.getInputStream(zipEntry)) {
      return copyWithSizeLimit(zipEntryStream, targetPath, remainingByteAllowance);
    }
  }

  /** Rejects a zip with more entries than {@code maxZipFileEntries}, before anything is extracted. */
  private static void validateEntryCount(ZipFile zipArchive, int maxZipFileEntries) {
    if (zipArchive.size() > maxZipFileEntries)
      throw new MigrationException(BUNDLE_TOO_LARGE, "Snapshot has more than " + maxZipFileEntries + " entries");
  }

  /** Rejects any entry that resolves outside {@code migrations/}, so seed data cannot be bundled. */
  private static void validateInsideMigrationsDir(Path targetPath, Path migrationsDir, ZipEntry zipEntry) {
    if (!targetPath.startsWith(migrationsDir))
      throw new MigrationException(
          BUNDLE_INVALID, "Snapshot entry outside " + MIGRATIONS_DIR_NAME + "/: " + zipEntry.getName());
  }

  /**
   * Requires every file to be named {@code V<version>__<description>.sql} or {@code R__<description>.sql}
   * and to sit directly in {@code migrations/}. Flyway would otherwise skip a misnamed file or fail
   * with a generic error mid-run.
   */
  private static void validateMigrationSqlFileName(Path targetSqlFilePath, Path migrationsDir, ZipEntry zipEntry) {
    String sqlFileName = targetSqlFilePath.getFileName().toString();
    if (!targetSqlFilePath.getParent().equals(migrationsDir) || !SQL_FILE_NAME_PATTERN.matcher(sqlFileName).matches())
      throw new MigrationException(
          VALIDATION_FAILED,
          "Misnamed migration " + zipEntry.getName()
              + ": expected migrations/V<version>__<description>.sql or migrations/R__<description>.sql");
  }

  /** Rejects a snapshot that contains no migration SQL files. */
  private static void validateAtLeastOneMigrationSqlFile(int extractedSqlFileCount) {
    if (extractedSqlFileCount == 0)
      throw new MigrationException(MIGRATIONS_NOT_FOUND, "Snapshot contains no migration SQL files under " + MIGRATIONS_DIR_NAME + "/");
  }

  /**
   * Resolves a zip entry name against {@code root} and returns the target path. Rejects empty, absolute,
   * backslash, drive-letter, NUL and {@code ..}/{@code .} entry names (zip slip), and any path that
   * escapes {@code root}.
   */
  static Path validateTargetPath(Path root, String zipEntryName) {
    if (zipEntryName.isEmpty()
        || zipEntryName.startsWith("/")
        || zipEntryName.startsWith("\\")
        || zipEntryName.contains("\\")
        || zipEntryName.contains(":")
        || zipEntryName.contains("\0"))
      throw new MigrationException(BUNDLE_INVALID, "Unsafe snapshot entry: " + zipEntryName);
    for (String segment : zipEntryName.split("/"))
      if (segment.equals("..") || segment.equals("."))
        throw new MigrationException(BUNDLE_INVALID, "Unsafe snapshot entry: " + zipEntryName);
    Path targetPath = root.resolve(zipEntryName).normalize();
    if (!targetPath.startsWith(root))
      throw new MigrationException(BUNDLE_INVALID, "Unsafe snapshot entry: " + zipEntryName);
    return targetPath;
  }

  /** Copies {@code zipInputStream} to {@code targetPath} and returns the bytes copied. Fails once {@code maxBytes} is exceeded. */
  private static long copyWithSizeLimit(InputStream zipInputStream, Path targetPath, long maxBytes) throws IOException {
    long copiedBytes = 0;
    byte[] copyByteBuffer = new byte[COPY_BUFFER_BYTES];
    try (OutputStream extractedFileOutputStream = Files.newOutputStream(targetPath)) {
      int bytesReadCount;
      while ((bytesReadCount = zipInputStream.read(copyByteBuffer)) != -1) {
        copiedBytes += bytesReadCount;
        if (copiedBytes > maxBytes)
          throw new MigrationException(BUNDLE_TOO_LARGE, "Snapshot exceeds " + maxBytes + " bytes");
        extractedFileOutputStream.write(copyByteBuffer, 0, bytesReadCount);
      }
    }
    return copiedBytes;
  }

  private static MessageDigest sha256() {
    try {
      return MessageDigest.getInstance("SHA-256");
    } catch (NoSuchAlgorithmException e) {
      throw new IllegalStateException(e);
    }
  }
}
