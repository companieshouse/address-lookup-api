package uk.gov.companieshouse.addresslookup.lambda.schema;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.stream.Stream;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;

/** Mirrors the Makefile's package-db-schema: the master changelog and changes/creation, never seeds or data. */
final class ChangelogPackaging {

    private ChangelogPackaging() {
    }

    static byte[] packageLikeMake(Path resourcesRoot) throws IOException {
        ByteArrayOutputStream bytes = new ByteArrayOutputStream();
        try (ZipOutputStream zip = new ZipOutputStream(bytes);
             Stream<Path> files = Files.walk(resourcesRoot.resolve("db/changelog"))) {
            for (Path file : files.filter(Files::isRegularFile).sorted().toList()) {
                String name = resourcesRoot.relativize(file).toString().replace('\\', '/');
                if (name.equals(MigratorConfiguration.MASTER_CHANGELOG) || name.startsWith("db/changelog/changes/creation/")) {
                    zip.putNextEntry(new ZipEntry(name));
                    zip.write(Files.readAllBytes(file));
                    zip.closeEntry();
                }
            }
        }
        return bytes.toByteArray();
    }
}
