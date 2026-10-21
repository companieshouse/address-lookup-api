package uk.gov.companieshouse.addresslookup.lambda.shared.acquisition;

import com.amazonaws.services.lambda.runtime.Context;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.junit.jupiter.api.Test;
import uk.gov.companieshouse.addresslookup.lambda.shared.os.OsClient;
import uk.gov.companieshouse.addresslookup.lambda.shared.storage.ReleaseStore;
import uk.gov.companieshouse.addresslookup.lambda.shared.storage.StreamingS3;
import uk.gov.companieshouse.addresslookup.lambda.shared.testsupport.FakeS3;

import java.io.ByteArrayInputStream;
import java.lang.reflect.Proxy;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.Duration;
import java.util.Arrays;
import java.util.HashMap;
import java.util.HexFormat;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;
import static uk.gov.companieshouse.addresslookup.lambda.shared.runtime.RuntimeSupport.JSON;

final class DownloadServiceTest {
    private static final String BUCKET = "source-bucket";
    private static final String PLAN_KEY =
            "acquisitions/2026-05-16/4b845f1a-b0dc-33f1-b76b-b76bb7cd0c56.json";
    private static final String RELEASE_ID = "4b845f1a-b0dc-33f1-b76b-b76bb7cd0c56";
    private static final String DATASET = "add_gb_royalmailaddress";

    @Test
    void downloadsOnlyTheDatasetNamedByTheEventBridgeCommand() throws Exception {
        byte[] zip = "local test zip bytes".getBytes(StandardCharsets.UTF_8);
        JsonNode manifest = manifestWithSelectedMd5(md5(zip));
        var s3 = new FakeS3();
        s3.objects.put(PLAN_KEY, JSON.writeValueAsBytes(manifest));
        String expectedUrl = manifest.path(DATASET).path("zip").path("url").asText();
        var os = mock(OsClient.class);
        when(os.download(expectedUrl)).thenReturn(new ByteArrayInputStream(zip));
        var service = new DownloadService(s3.client(), os, properties());

        assertEquals("ZIP_SCAN", service.download(event(), context()));

        String expectedZipKey = "Scanned/" + RELEASE_ID + "/" + DATASET + ".zip";
        verify(os).download(expectedUrl);
        assertEquals(List.of(expectedZipKey), s3.completedKeys);
        assertTrue(s3.completedKeys.stream().noneMatch(k -> k.contains("add_gb_builtaddress")));
        assertEquals(
                "Scanned/" + RELEASE_ID + "/" + DATASET + ".receipt.json",
                ReleaseStore.receiptKey(RELEASE_ID, DATASET, "download"));
        assertTrue(s3.objects.containsKey(ReleaseStore.receiptKey(RELEASE_ID, DATASET, "download")));
        JsonNode receipt =
                JSON.readTree(s3.objects.get(ReleaseStore.receiptKey(RELEASE_ID, DATASET, "download")));
        assertEquals(RELEASE_ID, receipt.path("releaseId").asText());
        assertEquals(DATASET, receipt.path("dataset").asText());
        assertEquals(DATASET + ".zip", receipt.path("fileName").asText());
        assertEquals(expectedZipKey, receipt.path("s3Key").asText());
        assertEquals(zip.length, receipt.path("downloadedBytes").asLong());
        assertEquals(md5(zip), receipt.path("calculatedMd5").asText());
        assertEquals(md5(zip), receipt.path("expectedMd5").asText());
        assertEquals("DOWNLOADED", receipt.path("status").asText());
        assertFalse(receipt.path("downloadedAt").asText().isBlank());
        assertEquals(expectedUrl, receipt.path("sourceUrl").asText());
        String checksumKey = "metadata/checksums/" + sha256(expectedZipKey.getBytes(StandardCharsets.UTF_8)) + ".json";
        JsonNode evidence = JSON.readTree(s3.objects.get(checksumKey));
        assertEquals(BUCKET, evidence.path("bucket").asText());
        assertEquals(expectedZipKey, evidence.path("key").asText());
        assertEquals(md5(zip), evidence.path("md5").asText());
        assertEquals(sha256(zip), evidence.path("sha256").asText());
        assertEquals(zip.length, evidence.path("bytes").asLong());
        assertFalse(evidence.path("versionId").asText().isBlank());
        assertTrue(s3.objects.keySet().stream().noneMatch(k -> k.endsWith(".download-attempt.json")));
    }

    @Test
    void rejectsNullEvent() {
        assertRejected(null, "Missing download event");
    }

    @Test
    void rejectsUnexpectedSource() throws Exception {
        var event = event();
        event.put("source", "other.source");

        assertRejected(event, "Unexpected download event");
    }

    @Test
    void rejectsUnexpectedDetailType() throws Exception {
        var event = event();
        event.put("detail-type", "SCAN");

        assertRejected(event, "Unexpected download event");
    }

    @Test
    void rejectsCommandWithoutDetail() throws Exception {
        var event = event();
        event.remove("detail");

        assertRejected(event, "Missing detail in download command");
    }

    @Test
    void rejectsCommandWithNullDetail() throws Exception {
        var event = event();
        event.put("detail", null);

        assertRejected(event, "Missing detail in download command");
    }

    @Test
    void rejectsCommandWithEmptyDetail() throws Exception {
        var event = event();
        event.put("detail", new HashMap<String, Object>());

        assertRejected(event, "Missing detail in download command");
    }

    @Test
    void rejectsCommandWithNonObjectDetail() throws Exception {
        var event = event();
        event.put("detail", "not-an-object");

        assertRejected(event, "Missing detail in download command");
    }

    @Test
    void rejectsCommandWithoutPlanKey() throws Exception {
        var event = event();
        event.put("detail", Map.of("dataset", DATASET));

        assertRejected(event, "Missing detail.planKey in download command");
    }

    @Test
    void rejectsCommandWithNullBlankOrNonTextPlanKey() throws Exception {
        for (Object planKey : Arrays.asList(null, "", "   ", 123, true, List.of(PLAN_KEY))) {
            var detail = new HashMap<String, Object>();
            detail.put("planKey", planKey);
            detail.put("dataset", DATASET);
            var event = event();
            event.put("detail", detail);

            assertRejected(event, "Missing detail.planKey in download command");
        }
    }

    @Test
    void rejectsCommandWithInvalidPlanKeyFormatBeforeReadingS3() throws Exception {
        var event = event();
        event.put("detail", Map.of("planKey", "acquisitions/not-a-date/not-a-uuid.json", "dataset", DATASET));

        assertRejected(event, "Invalid plan key");
    }

    @Test
    void rejectsCommandWithoutDatasetBeforeReadingS3() throws Exception {
        var event = event();
        event.put("detail", Map.of("planKey", PLAN_KEY));

        assertRejected(event, "Missing detail.dataset in download command");
    }

    @Test
    void rejectsCommandWithNullBlankOrNonTextDataset() throws Exception {
        for (Object dataset : Arrays.asList(null, "", "   ", 123, true, List.of(DATASET))) {
            var detail = new HashMap<String, Object>();
            detail.put("planKey", PLAN_KEY);
            detail.put("dataset", dataset);
            var event = event();
            event.put("detail", detail);

            assertRejected(event, "Missing detail.dataset in download command");
        }
    }

    @Test
    void rejectsCommandWithUnknownDataset() throws Exception {
        var event = event();
        event.put("detail", Map.of("planKey", PLAN_KEY, "dataset", "not_a_real_dataset"));
        var s3 = new FakeS3();
        var os = mock(OsClient.class);
        var service = new DownloadService(s3.client(), os, properties());
        var context = context();

        assertThrows(Exception.class, () -> service.download(event, context));

        assertTrue(s3.objects.isEmpty());
        verifyNoInteractions(os);
    }

    @Test
    void trimsPlanKeyAndDatasetBeforeProcessing() throws Exception {
        byte[] zip = "trimmed command zip".getBytes(StandardCharsets.UTF_8);
        var s3 = new FakeS3();
        s3.objects.put(PLAN_KEY, JSON.writeValueAsBytes(manifestWithSelectedMd5(md5(zip))));
        var os = mock(OsClient.class);
        when(os.download(anyString())).thenReturn(new ByteArrayInputStream(zip));
        var event = event();
        event.put("detail", Map.of("planKey", "  " + PLAN_KEY + "  ", "dataset", "  " + DATASET + "  "));
        var service = new DownloadService(s3.client(), os, properties());

        assertEquals("ZIP_SCAN", service.download(event, context()));
        assertTrue(s3.objects.containsKey(ReleaseStore.receiptKey(RELEASE_ID, DATASET, "download")));
    }

    @Test
    void doesNotPublishDownloadCompleteEventAfterSuccessfulDownload() throws Exception {
        byte[] zip = "event zip bytes".getBytes(StandardCharsets.UTF_8);
        var s3 = new FakeS3();
        s3.objects.put(PLAN_KEY, JSON.writeValueAsBytes(manifestWithSelectedMd5(md5(zip))));
        var os = mock(OsClient.class);
        when(os.download(anyString())).thenReturn(new ByteArrayInputStream(zip));
        var service = new DownloadService(s3.client(), os, properties());

        assertEquals("ZIP_SCAN", service.download(event(), context()));

        assertTrue(s3.objects.containsKey(ReleaseStore.receiptKey(RELEASE_ID, DATASET, "download")));
    }

    @Test
    void doesNotPublishEventOnChecksumMismatch() throws Exception {
        var s3 = new FakeS3();
        s3.objects.put(PLAN_KEY, fixtureBytes("4b845f1a-b0dc-33f1-b76b-b76bb7cd0c56.json"));
        var os = mock(OsClient.class);
        when(os.download(anyString())).thenReturn(new ByteArrayInputStream("wrong content".getBytes(StandardCharsets.UTF_8)));
        var service = new DownloadService(s3.client(), os, properties());

        assertEquals("CHECKSUM_MISMATCH", service.download(event(), context()));

    }

    @Test
    void doesNotPublishEventWhenReceiptAlreadyExists() throws Exception {
        var s3 = new FakeS3();
        s3.objects.put(PLAN_KEY, fixtureBytes("4b845f1a-b0dc-33f1-b76b-b76bb7cd0c56.json"));
        s3.objects.put(ReleaseStore.receiptKey(RELEASE_ID, DATASET, "download"), "{}".getBytes(StandardCharsets.UTF_8));
        var os = mock(OsClient.class);
        var service = new DownloadService(s3.client(), os, properties());

        assertEquals("SKIPPED", service.download(event(), context()));

        verifyNoInteractions(os);
    }

    @Test
    void failsWithoutDownloadingWhenPlanFileDoesNotExist() throws Exception {
        var s3 = new FakeS3();
        var os = mock(OsClient.class);
        var service = new DownloadService(s3.client(), os, properties());
        var event = event();
        var context = context();
        String expected = "Acquisition plan not found planKey=" + PLAN_KEY + " dataset=" + DATASET;
        var out = new java.io.ByteArrayOutputStream();
        var originalOut = System.out;
        System.setOut(new java.io.PrintStream(out, true, StandardCharsets.UTF_8));
        try {
            var e = assertThrows(IllegalArgumentException.class, () -> service.download(event, context));
            assertEquals(expected, e.getMessage());
            verifyNoInteractions(os);
        } finally {
            System.setOut(originalOut);
        }
        // LambdaRuntime.getLogger() writes to System.out outside the Lambda runtime; the level is not rendered.
        assertTrue(out.toString(StandardCharsets.UTF_8).contains(expected));
    }
    @Test
    void receiptIsCreatedConditionallyAndNeverOverwritten() throws Exception {
        var s3 = new FakeS3();
        String receiptKey = ReleaseStore.receiptKey(RELEASE_ID, DATASET, "download");
        byte[] original = "{\"status\":\"ORIGINAL\"}".getBytes(StandardCharsets.UTF_8);
        s3.objects.put(receiptKey, original);
        var upload = new StreamingS3.Uploaded("Scanned/" + RELEASE_ID + "/" + DATASET + ".zip", "v1", "sha", "md5", 1L);

        // A 412 from the conditional create is tolerated and the existing receipt stays untouched.
        new ReleaseStore(s3.client())
                .downloadReceipt(
                        BUCKET,
                        new ReleaseStore.DownloadReceipt(
                                RELEASE_ID, DATASET, DATASET + ".zip", "md5", "https://api.os.uk/x", java.time.Instant.now()),
                        upload);

        assertArrayEquals(original, s3.objects.get(receiptKey));
    }

    private static void assertRejected(Map<String, Object> event, String expectedMessage) {
        var s3 = new FakeS3();
        var os = mock(OsClient.class);
        var service = new DownloadService(s3.client(), os, properties());
        var context = context();

        var failure = assertThrows(IllegalArgumentException.class, () -> service.download(event, context));

        assertEquals(expectedMessage, failure.getMessage());
        assertTrue(s3.objects.isEmpty());
        assertTrue(s3.uploads.isEmpty());
        verifyNoInteractions(os);
    }

    @Test
    void eachDatasetCommandDownloadsOnlyItsNamedZip() throws Exception {
        for (String dataset : List.of(
                "add_gb_builtaddress",
                "add_gb_royalmailaddress",
                "add_isl_builtaddress",
                "add_isl_royalmailaddress")) {
            byte[] zip = ("zip for " + dataset).getBytes(StandardCharsets.UTF_8);
            var manifest = manifestWithMd5(dataset, md5(zip));
            var s3 = new FakeS3();
            s3.objects.put(PLAN_KEY, JSON.writeValueAsBytes(manifest));
            var event = event();
            event.put("detail", Map.of("planKey", PLAN_KEY, "dataset", dataset));
            var os = mock(OsClient.class);
            when(os.download(anyString())).thenReturn(new ByteArrayInputStream(zip));
            var service = new DownloadService(s3.client(), os, properties());

            assertEquals("ZIP_SCAN", service.download(event, context()));
            assertEquals(List.of("Scanned/" + RELEASE_ID + "/" + dataset + ".zip"), s3.completedKeys);
            assertTrue(s3.objects.containsKey(ReleaseStore.receiptKey(RELEASE_ID, dataset, "download")));
            assertTrue(s3.objects.keySet().stream().noneMatch(k -> k.endsWith(".download-attempt.json")));
        }
    }

    @Test
    void checksumMismatchAbortsUploadAndDoesNotPublishReceipt() throws Exception {
        var s3 = new FakeS3();
        s3.objects.put(PLAN_KEY, fixtureBytes("4b845f1a-b0dc-33f1-b76b-b76bb7cd0c56.json"));
        var os = mock(OsClient.class);
        when(os.download(anyString())).thenReturn(new ByteArrayInputStream("wrong content".getBytes(StandardCharsets.UTF_8)));
        var service = new DownloadService(s3.client(), os, properties());
        var out = new java.io.ByteArrayOutputStream();
        var originalOut = System.out;
        System.setOut(new java.io.PrintStream(out, true, StandardCharsets.UTF_8));
        try {
            assertEquals("CHECKSUM_MISMATCH", service.download(event(), context()));
        } finally {
            System.setOut(originalOut);
        }
        // LambdaRuntime.getLogger() writes to System.out outside the Lambda runtime; the level is not rendered.
        String logged = out.toString(StandardCharsets.UTF_8);
        assertTrue(
                logged.contains("Download checksum mismatch")
                        && logged.contains("expected=")
                        && logged.contains("actual="),
                "mismatch must be logged with both MD5 values");
        assertTrue(s3.abortedUploads > 0, "multipart upload should be aborted");
        assertFalse(
                s3.objects.containsKey(ReleaseStore.receiptKey(RELEASE_ID, DATASET, "download")),
                "checksum mismatch must not publish a receipt");
        assertTrue(s3.completedKeys.isEmpty(), "checksum mismatch must not complete a ZIP object");
        assertTrue(s3.objects.keySet().stream().noneMatch(k -> k.endsWith(".download-attempt.json")));
    }

    @Test
    void existingReceiptMakesDownloadIdempotent() throws Exception {
        var s3 = new FakeS3();
        s3.objects.put(PLAN_KEY, fixtureBytes("4b845f1a-b0dc-33f1-b76b-b76bb7cd0c56.json"));
        s3.objects.put(
                ReleaseStore.receiptKey(RELEASE_ID, DATASET, "download"),
                JSON.writeValueAsBytes(
                        Map.of(
                                "releaseId", RELEASE_ID,
                                "dataset", DATASET,
                                "fileName", DATASET + ".zip",
                                "s3Key", "Scanned/" + RELEASE_ID + "/" + DATASET + ".zip",
                                "downloadedBytes", 1,
                                "calculatedMd5", "17db768ba6b7a85c5e34d5e200930ff5",
                                "expectedMd5", "17db768ba6b7a85c5e34d5e200930ff5",
                                "status", "DOWNLOADED",
                                "downloadedAt", "2026-05-16T09:15:23Z",
                                "sourceUrl", "https://api.os.uk/example")));
        var os = mock(OsClient.class);
        var service = new DownloadService(s3.client(), os, properties());

        assertEquals("SKIPPED", service.download(event(), context()));
        verifyNoInteractions(os);
    }

    private static AcquisitionProperties properties() {
        return new AcquisitionProperties(
                BUCKET,
                "scanned-bucket",
                "event-bus",
                "redacted",
                "{}",
                512L * 1024L * 1024L,
                1024L * 1024L * 1024L,
                20,
                Duration.ofSeconds(20),
                Duration.ofSeconds(840));
    }

    private static Map<String, Object> event() throws Exception {
        return JSON.readValue(fixtureBytes("mock-eventbridge-example.json"), new TypeReference<>() {
        });
    }

    private static JsonNode manifestWithSelectedMd5(String md5) throws Exception {
        return manifestWithMd5(DATASET, md5);
    }

    private static JsonNode manifestWithMd5(String dataset, String md5) throws Exception {
        ObjectNode manifest =
                (ObjectNode) JSON.readTree(fixtureBytes("4b845f1a-b0dc-33f1-b76b-b76bb7cd0c56.json"));
        ((ObjectNode) manifest.path(dataset).path("zip")).put("md5", md5);
        return manifest;
    }

    private static byte[] fixtureBytes(String name) throws Exception {
        try (var in = DownloadServiceTest.class.getResourceAsStream("/example-download-lambda/" + name)) {
            assertNotNull(in, "Missing fixture " + name);
            return in.readAllBytes();
        }
    }

    private static Context context() {
        return (Context)
                Proxy.newProxyInstance(
                        Context.class.getClassLoader(),
                        new Class<?>[]{Context.class},
                        (proxy, method, args) ->
                                switch (method.getName()) {
                                    case "getRemainingTimeInMillis" -> 900_000;
                                    case "getMemoryLimitInMB" -> 1024;
                                    default -> null;
                                });
    }

    private static String md5(byte[] bytes) throws Exception {
        return HexFormat.of().formatHex(MessageDigest.getInstance("MD5").digest(bytes));
    }

    private static String sha256(byte[] bytes) throws Exception {
        return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(bytes));
    }
}
