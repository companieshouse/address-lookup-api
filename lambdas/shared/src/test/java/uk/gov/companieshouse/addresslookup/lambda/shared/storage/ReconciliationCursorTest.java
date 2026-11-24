package uk.gov.companieshouse.addresslookup.lambda.shared.storage;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.argThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import static java.nio.charset.StandardCharsets.UTF_8;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import software.amazon.awssdk.services.s3.S3Client;
import software.amazon.awssdk.services.s3.model.ListObjectsV2Request;
import software.amazon.awssdk.services.s3.model.ListObjectsV2Response;
import software.amazon.awssdk.services.s3.model.S3Exception;
import software.amazon.awssdk.services.s3.model.S3Object;

import uk.gov.companieshouse.addresslookup.lambda.shared.testsupport.FakeS3;
import uk.gov.companieshouse.addresslookup.lambda.shared.util.KeyLayout;

import java.io.IOException;
import java.util.Optional;

final class ReconciliationCursorTest {
    private static final String CURSOR = "reconciliation/sweep.json";

    private final FakeS3 s3 = new FakeS3();
    private ReconciliationCursor cursor;

    @BeforeEach
    void setUp() {
        cursor = new ReconciliationCursor(s3.client(), "bucket", "bucket", "incoming/", "sweep");
    }

    private void store(String key) {
        s3.objects.put(key, new byte[0]);
    }

    private void storeCursor(String json) {
        s3.objects.put(CURSOR, json.getBytes(UTF_8));
    }

    @Test
    void emptyPrefixYieldsNothing() throws IOException {
        assertEquals(Optional.empty(), cursor.next());
    }

    @Test
    void skipsZipAndStateAndWrapsBetweenManifests() throws IOException {
        var version = new KeyLayout.PackageVersion(KeyLayout.Feed.DAILY_COU, "20780", "1");
        String manifest = KeyLayout.dataPackageManifestKey(version);
        store(
                KeyLayout.downloadZipKey(
                        version,
                        uk.gov.companieshouse.release.model.Dataset.ADD_GB_ROYALMAILADDRESS));
        store(KeyLayout.unpackStateKey(version));
        store(manifest);
        var manifests =
                new ReconciliationCursor(
                        s3.client(),
                        "control",
                        "landing",
                        KeyLayout.feedManifestPrefix(version.feed()),
                        "sweep",
                        KeyLayout::isDataPackageManifestKey);

        assertEquals(Optional.of(manifest), manifests.next());
        assertEquals(Optional.of(manifest), manifests.next());
    }

    @Test
    void doesNotWriteCursorWhenOnlyNonManifestObjectsExist() throws IOException {
        var version = new KeyLayout.PackageVersion(KeyLayout.Feed.DAILY_COU, "20780", "1");
        store(KeyLayout.unpackStateKey(version));
        var manifests =
                new ReconciliationCursor(
                        s3.client(),
                        "control",
                        "landing",
                        KeyLayout.feedManifestPrefix(version.feed()),
                        "sweep",
                        KeyLayout::isDataPackageManifestKey);

        assertEquals(Optional.empty(), manifests.next());
        assertFalse(s3.objects.containsKey(CURSOR));
    }

    @Test
    void scansNextListingPageUntilManifestFound() throws IOException {
        var client = mock(S3Client.class);
        String manifest = "daily_cou/20780/1/manifest/data_package_manifest.json";
        when(client.listObjectsV2(any(ListObjectsV2Request.class)))
                .thenAnswer(
                        call -> {
                            var request = call.getArgument(0, ListObjectsV2Request.class);
                            if (CURSOR.equals(request.prefix()))
                                return ListObjectsV2Response.builder().build();
                            return request.continuationToken() == null
                                    ? ListObjectsV2Response.builder()
                                            .contents(
                                                    S3Object.builder()
                                                            .key(
                                                                    "daily_cou/20780/1/downloads/data.zip")
                                                            .build())
                                            .nextContinuationToken("page-2")
                                            .build()
                                    : ListObjectsV2Response.builder()
                                            .contents(S3Object.builder().key(manifest).build())
                                            .build();
                        });
        var manifests =
                new ReconciliationCursor(
                        client,
                        "control",
                        "landing",
                        "daily_cou/",
                        "sweep",
                        KeyLayout::isDataPackageManifestKey);

        assertEquals(Optional.of(manifest), manifests.next());
        verify(client)
                .putObject(
                        argThat(
                                (software.amazon.awssdk.services.s3.model.PutObjectRequest
                                                request) -> CURSOR.equals(request.key())),
                        any(software.amazon.awssdk.core.sync.RequestBody.class));
    }

    @Test
    void advancesThroughKeysAndWrapsAround() throws IOException {
        store("incoming/a.csv");
        store("incoming/b.csv");

        assertEquals(Optional.of("incoming/a.csv"), cursor.next());
        assertEquals(Optional.of("incoming/b.csv"), cursor.next());
        assertEquals(Optional.of("incoming/a.csv"), cursor.next());
        assertTrue(
                new String(s3.objects.get(CURSOR), UTF_8).contains("\"after\":\"incoming/a.csv\""));
    }

    @Test
    void resumesAfterStoredPosition() throws IOException {
        store("incoming/a.csv");
        store("incoming/b.csv");
        storeCursor("{\"after\":\"incoming/a.csv\"}");

        assertEquals(Optional.of("incoming/b.csv"), cursor.next());
    }

    @Test
    void rejectsOversizedCursor() {
        store("incoming/a.csv");
        storeCursor("{\"after\":\"incoming/" + "x".repeat(5000) + "\"}");

        var ex = assertThrows(IllegalArgumentException.class, cursor::next);

        assertEquals("Reconciliation cursor too large", ex.getMessage());
    }

    @Test
    void rejectsCursorOutsidePrefix() {
        store("incoming/a.csv");
        storeCursor("{\"after\":\"other/a.csv\"}");

        var ex = assertThrows(IllegalArgumentException.class, cursor::next);

        assertEquals("Unexpected reconciliation cursor prefix", ex.getMessage());
    }

    @Test
    void rejectsCursorWithoutPosition() {
        store("incoming/a.csv");
        storeCursor("{}");

        assertThrows(IllegalArgumentException.class, cursor::next);
    }

    @Test
    void retriesAfterLosingRace() throws IOException {
        store("incoming/a.csv");
        s3.putConflicts = 1;

        assertEquals(Optional.of("incoming/a.csv"), cursor.next());
    }

    @Test
    void failsWhenCursorStaysContended() {
        store("incoming/a.csv");
        s3.putConflicts = 5;

        var ex = assertThrows(IllegalStateException.class, cursor::next);

        assertEquals("Reconciliation cursor busy; retry next invocation", ex.getMessage());
    }

    @Test
    void propagatesUnexpectedStorageErrors() {
        store("incoming/a.csv");
        s3.putErrorStatus = 500;

        assertThrows(S3Exception.class, cursor::next);
    }
}
