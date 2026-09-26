package uk.gov.companieshouse.addresslookup.lamda.schema;

import software.amazon.awssdk.core.ResponseInputStream;
import software.amazon.awssdk.services.s3.S3Client;
import software.amazon.awssdk.services.s3.model.GetObjectResponse;
import uk.gov.companieshouse.addresslookup.lamda.schema.MigratorConfiguration.ArchiveLimits;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;
import java.util.Locale;
import java.util.Set;
import java.util.zip.ZipEntry;
import java.util.zip.ZipInputStream;

import static uk.gov.companieshouse.addresslookup.lamda.schema.MigrationRequest.require;

/**
 * Fetches a released changelog archive and unpacks it for Liquibase.
 *
 * <p>The archive is untrusted input to a function holding privileged database credentials, so it is size-capped,
 * checked against the SHA-256 the pipeline published, and extracted with path traversal, entry count, expanded size
 * and file type guards.
 */
public final class ChangelogArchive {

    private static final Set<String> ALLOWED_EXTENSIONS = Set.of(".yaml", ".yml", ".xml", ".sql", ".json");

    private final S3Client s3;
    private final MigratorConfiguration config;

    public ChangelogArchive(S3Client s3, MigratorConfiguration config) {
        this.s3 = s3;
        this.config = config;
    }

    /** Downloads, verifies and extracts the archive for the request, returning the Liquibase search path root. */
    public Path fetch(MigrationRequest request, Path workDirectory) throws IOException {
        String key = config.changelogKey(request.version());
        byte[] archive = download(key, config.archiveLimits().maxArchiveBytes());
        verifyDigest(archive, request.sha256(), key);
        extract(archive, workDirectory, config.archiveLimits());
        require(Files.isRegularFile(workDirectory.resolve(MigratorConfiguration.MASTER_CHANGELOG)),
                key + " does not contain " + MigratorConfiguration.MASTER_CHANGELOG);
        return workDirectory;
    }

    private byte[] download(String key, long maxBytes) throws IOException {
        try (ResponseInputStream<GetObjectResponse> object =
                     s3.getObject(get -> get.bucket(config.changelogBucket()).key(key))) {
            Long length = object.response().contentLength();
            require(length == null || length <= maxBytes, key + " exceeds " + maxBytes + " bytes");
            byte[] bytes = object.readNBytes((int) maxBytes + 1);
            require(bytes.length <= maxBytes, key + " exceeds " + maxBytes + " bytes");
            return bytes;
        }
    }

    static void verifyDigest(byte[] archive, String expectedSha256, String key) {
        byte[] actual = sha256(archive);
        byte[] expected = HexFormat.of().parseHex(expectedSha256);
        if (!MessageDigest.isEqual(actual, expected)) {
            throw new IllegalArgumentException("SHA-256 of " + key + " is " + HexFormat.of().formatHex(actual)
                    + ", not the released " + expectedSha256);
        }
    }

    static void extract(byte[] archive, Path destination, ArchiveLimits limits) throws IOException {
        Path root = destination.toAbsolutePath().normalize();
        long expanded = 0;
        int entries = 0;

        try (ZipInputStream zip = new ZipInputStream(new ByteArrayInputStream(archive))) {
            for (ZipEntry entry = zip.getNextEntry(); entry != null; entry = zip.getNextEntry()) {
                require(++entries <= limits.maxEntries(), "Archive has more than " + limits.maxEntries() + " entries");

                String name = entry.getName();
                require(!name.isEmpty() && !name.startsWith("/") && !name.contains("\\") && !name.contains(":"),
                        "Illegal archive entry name: " + name);
                Path target = root.resolve(name).normalize();
                require(target.startsWith(root) && !target.equals(root), "Archive entry escapes the work directory: " + name);

                if (entry.isDirectory()) {
                    Files.createDirectories(target);
                    continue;
                }

                String lower = name.toLowerCase(Locale.ROOT);
                require(ALLOWED_EXTENSIONS.stream().anyMatch(lower::endsWith), "Unexpected file type in archive: " + name);

                Files.createDirectories(target.getParent());
                // CREATE_NEW: a duplicate entry must fail rather than silently replace an earlier one.
                try (OutputStream out = Files.newOutputStream(target, StandardOpenOption.CREATE_NEW)) {
                    expanded += copy(zip, out, limits.maxExtractedBytes() - expanded);
                }
            }
        }
        require(entries > 0, "Archive is empty");
    }

    /** Copies at most {@code budget} bytes, failing rather than truncating if the entry is larger. */
    private static long copy(InputStream in, OutputStream out, long budget) throws IOException {
        byte[] buffer = new byte[8192];
        long total = 0;
        for (int read = in.read(buffer); read != -1; read = in.read(buffer)) {
            total += read;
            require(total <= budget, "Archive expands beyond the permitted size");
            out.write(buffer, 0, read);
        }
        return total;
    }

    static byte[] sha256(byte[] bytes) {
        try {
            return MessageDigest.getInstance("SHA-256").digest(bytes);
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException(e);
        }
    }
}
