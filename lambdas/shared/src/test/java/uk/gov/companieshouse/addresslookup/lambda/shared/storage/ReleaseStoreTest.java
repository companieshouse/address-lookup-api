package uk.gov.companieshouse.addresslookup.lambda.shared.storage;

import org.junit.jupiter.api.Test;
import software.amazon.awssdk.core.sync.RequestBody;
import software.amazon.awssdk.services.s3.S3Client;
import software.amazon.awssdk.services.s3.model.GetObjectRequest;
import software.amazon.awssdk.services.s3.model.ListObjectsV2Request;
import software.amazon.awssdk.services.s3.model.ListObjectsV2Response;
import software.amazon.awssdk.services.s3.model.PutObjectRequest;
import software.amazon.awssdk.services.s3.model.S3Exception;
import software.amazon.awssdk.services.s3.model.S3Object;
import uk.gov.companieshouse.addresslookup.lambda.shared.testsupport.FakeS3;

import java.nio.charset.StandardCharsets;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;
import static uk.gov.companieshouse.addresslookup.lambda.shared.runtime.RuntimeSupport.JSON;

final class ReleaseStoreTest {
    private static final String BUCKET = "source-bucket";
    private static final String KEY = "Scanned/zip.zip";
    private static final String ID = "4b845f1a-b0dc-33f1-b76b-b76bb7cd0c56";
    private static final String DATASET = "add_gb_royalmailaddress";

    private final FakeS3 s3 = new FakeS3();
    private final ReleaseStore store = new ReleaseStore(s3.client());

    @Test
    void readReturnsNullWhenKeyDoesNotExist() throws Exception {
        assertNull(store.read(BUCKET, "acquisitions/missing.json"));
    }

    @Test
    void readParsesExistingJson() throws Exception {
        s3.objects.put("plan.json", "{\"releaseId\":\"abc\"}".getBytes(StandardCharsets.UTF_8));

        assertEquals("abc", store.read(BUCKET, "plan.json").path("releaseId").asText());
    }

    @Test
    void readRejectsObjectsLargerThanOneMebibyte() {
        s3.objects.put("big.json", new byte[1024 * 1024 + 1]);

        var failure = assertThrows(IllegalArgumentException.class, () -> store.read(BUCKET, "big.json"));

        assertEquals("Manifest exceeds 1 MiB", failure.getMessage());
    }

    @Test
    void readReturnsNullWhenObjectDisappearsBetweenListAndGet() throws Exception {
        var client = clientListingKey("plan.json");
        when(client.getObject(any(GetObjectRequest.class)))
                .thenThrow(S3Exception.builder().statusCode(404).message("gone").build());

        assertNull(new ReleaseStore(client).read(BUCKET, "plan.json"));
    }

    @Test
    void readRethrowsOtherS3Failures() {
        var client = clientListingKey("plan.json");
        when(client.getObject(any(GetObjectRequest.class)))
                .thenThrow(S3Exception.builder().statusCode(403).message("denied").build());

        var failingStore = new ReleaseStore(client);
        var failure = assertThrows(S3Exception.class, () -> failingStore.read(BUCKET, "plan.json"));

        assertEquals(403, failure.statusCode());
    }

    @Test
    void immutableWritesOnceAndIgnoresExistingObject() throws Exception {
        store.immutable(BUCKET, "receipt.json", JSON.readTree("{\"status\":\"FIRST\"}"));
        store.immutable(BUCKET, "receipt.json", JSON.readTree("{\"status\":\"SECOND\"}"));

        assertEquals("FIRST", JSON.readTree(s3.objects.get("receipt.json")).path("status").asText());
    }

    @Test
    void immutableRethrowsUnexpectedS3Failures() {
        var client = mock(S3Client.class);
        when(client.putObject(any(PutObjectRequest.class), any(RequestBody.class)))
                .thenThrow(S3Exception.builder().statusCode(500).message("boom").build());

        var failingStore = new ReleaseStore(client);
        var value = JSON.createObjectNode();
        assertThrows(S3Exception.class, () -> failingStore.immutable(BUCKET, "receipt.json", value));
    }

    @Test
    void keysListsObjectsUnderPrefix() {
        s3.objects.put("control/a.json", new byte[0]);
        s3.objects.put("control/b.json", new byte[0]);
        s3.objects.put("other/c.json", new byte[0]);

        assertEquals(List.of("control/a.json", "control/b.json"), store.keys(BUCKET, "control/"));
    }

    @Test
    void versionReturnsTheVersionedObjectId() {
        s3.versions.put(KEY, "abc123");

        assertEquals("abc123", store.version(BUCKET, KEY));
    }

    @Test
    void versionRejectsUnversionedBuckets() {
        s3.versions.put(KEY, "null");

        var failure = assertThrows(IllegalArgumentException.class, () -> store.version(BUCKET, KEY));

        assertEquals("Versioned bucket required", failure.getMessage());
    }

    @Test
    void scanDefaultsToPendingWhenNoTagExists() {
        assertEquals("PENDING", store.scan(BUCKET, KEY, "v1"));
        assertFalse(store.isClean(BUCKET, KEY, "v1"));
    }

    @Test
    void isCleanIsTrueOnlyForNoThreatsFound() {
        s3.scanStatus.put(KEY, ReleaseStore.CLEAN);

        assertTrue(store.isClean(BUCKET, KEY, "v1"));
    }

    @Test
    void isCleanFailsForCompletedUnsuccessfulScan() {
        s3.scanStatus.put(KEY, "THREATS_FOUND");

        var failure = assertThrows(IllegalStateException.class, () -> store.isClean(BUCKET, KEY, "v1"));

        assertTrue(failure.getMessage().contains("THREATS_FOUND"));
        assertTrue(failure.getMessage().contains(KEY));
    }

    @Test
    void cleanUsesTheVersionInTheReference() {
        s3.scanStatus.put(KEY, ReleaseStore.CLEAN);
        var ref = JSON.createObjectNode().put("s3Key", KEY).put("version", FakeS3.DEFAULT_VERSION);

        assertTrue(store.clean(BUCKET, ref));
    }

    @Test
    void cleanFallsBackToKeyAndCurrentVersionWhenReferenceHasNeither() {
        s3.scanStatus.put(KEY, ReleaseStore.CLEAN);
        var ref = JSON.createObjectNode().put("key", KEY);

        assertTrue(store.clean(BUCKET, ref));
    }

    @Test
    void cleanIsFalseWhileScanIsPending() {
        var ref = JSON.createObjectNode().put("s3Key", KEY);

        assertFalse(store.clean(BUCKET, ref));
    }

    @Test
    void cleanRejectsObjectsChangedAfterCertification() {
        s3.scanStatus.put(KEY, ReleaseStore.CLEAN);
        var ref = JSON.createObjectNode().put("s3Key", KEY).put("version", "certified-version");

        var failure = assertThrows(IllegalArgumentException.class, () -> store.clean(BUCKET, ref));

        assertEquals("Object changed after certification: " + KEY, failure.getMessage());
    }

    @Test
    void objectKeyPrefersS3KeyOverKey() {
        assertEquals("a", ReleaseStore.objectKey(JSON.createObjectNode().put("s3Key", "a").put("key", "b")));
        assertEquals("b", ReleaseStore.objectKey(JSON.createObjectNode().put("key", "b")));
    }

    @Test
    void objectVersionUsesReferenceVersionOrLooksItUp() {
        s3.versions.put(KEY, "looked-up");

        assertEquals(
                "from-ref",
                ReleaseStore.objectVersion(store, BUCKET, JSON.createObjectNode().put("s3Key", KEY).put("version", "from-ref")));
        assertEquals(
                "looked-up",
                ReleaseStore.objectVersion(store, BUCKET, JSON.createObjectNode().put("s3Key", KEY)));
    }

    @Test
    void planKeyIsBuiltFromTargetAndReleaseId() {
        var plan = JSON.createObjectNode().put("target", "2026-05-16").put("releaseId", ID);

        assertEquals("acquisitions/2026-05-16/" + ID + ".json", ReleaseStore.planKey(plan));
    }

    @Test
    void receiptKeyDiffersForDownloadAndOtherSteps() {
        assertEquals("Scanned/" + ID + "/" + DATASET + ".receipt.json", ReleaseStore.receiptKey(ID, DATASET, "download"));
        assertEquals("control/" + ID + "/" + DATASET + "/extract.json", ReleaseStore.receiptKey(ID, DATASET, "extract"));
        assertThrows(IllegalArgumentException.class, () -> ReleaseStore.receiptKey("not-a-uuid", DATASET, "download"));
    }

    @Test
    void downloadZipKeyUsesScannedPrefix() {
        assertEquals("Scanned/" + ID + "/" + DATASET + ".zip", ReleaseStore.downloadZipKey(ID, DATASET));
    }

    @Test
    void receiptRoundTripsAnUploadRecord() throws Exception {
        assertNull(store.receipt(BUCKET, ID, DATASET, "extract"));
        var upload = new StreamingS3.Uploaded("quarantine/csv/x.csv", "v9", "sha", "md5", 42L);

        store.receipt(BUCKET, ID, DATASET, "extract", upload);

        var saved = store.receipt(BUCKET, ID, DATASET, "extract");
        assertEquals("quarantine/csv/x.csv", saved.path("key").asText());
        assertEquals("v9", saved.path("version").asText());
        assertEquals(42L, saved.path("bytes").asLong());
    }

    private static S3Client clientListingKey(String key) {
        var client = mock(S3Client.class);
        when(client.listObjectsV2(any(ListObjectsV2Request.class)))
                .thenReturn(ListObjectsV2Response.builder().contents(S3Object.builder().key(key).build()).build());
        return client;
    }
}

