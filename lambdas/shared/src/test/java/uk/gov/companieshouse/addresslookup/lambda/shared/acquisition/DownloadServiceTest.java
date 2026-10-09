package uk.gov.companieshouse.addresslookup.lambda.shared.acquisition;

import com.amazonaws.services.lambda.runtime.Context;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.junit.jupiter.api.Test;
import software.amazon.awssdk.core.ResponseInputStream;
import software.amazon.awssdk.core.sync.RequestBody;
import software.amazon.awssdk.http.AbortableInputStream;
import software.amazon.awssdk.services.s3.S3Client;
import software.amazon.awssdk.services.s3.model.*;
import uk.gov.companieshouse.addresslookup.lambda.shared.os.OsClient;
import uk.gov.companieshouse.addresslookup.lambda.shared.storage.ReleaseStore;
import uk.gov.companieshouse.addresslookup.lambda.shared.storage.StreamingS3;

import java.io.ByteArrayInputStream;
import java.lang.reflect.Proxy;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.Duration;
import java.util.*;
import java.util.function.Consumer;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.*;
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
        var service = new DownloadService(s3.client(), os, mock(Commands.class), properties());

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
    void rejectsNullEvent() throws Exception {
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
        var service = new DownloadService(s3.client(), os, mock(Commands.class), properties());

        assertThrows(Exception.class, () -> service.download(event, context()));

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
        var service = new DownloadService(s3.client(), os, mock(Commands.class), properties());

        assertEquals("ZIP_SCAN", service.download(event, context()));
        assertTrue(s3.objects.containsKey(ReleaseStore.receiptKey(RELEASE_ID, DATASET, "download")));
    }

    @Test
    void publishesDownloadCompleteEventOnlyAfterMd5MatchesAndReceiptIsWritten() throws Exception {
        byte[] zip = "event zip bytes".getBytes(StandardCharsets.UTF_8);
        var s3 = new FakeS3();
        s3.objects.put(PLAN_KEY, JSON.writeValueAsBytes(manifestWithSelectedMd5(md5(zip))));
        var os = mock(OsClient.class);
        when(os.download(anyString())).thenReturn(new ByteArrayInputStream(zip));
        var commands = mock(Commands.class);
        doAnswer(
                invocation -> {
                    assertTrue(
                            s3.objects.containsKey(ReleaseStore.receiptKey(RELEASE_ID, DATASET, "download")),
                            "receipt must exist before the event is published");
                    return null;
                })
                .when(commands)
                .send(anyString(), anyString(), anyString());
        var service = new DownloadService(s3.client(), os, commands, properties());

        assertEquals("ZIP_SCAN", service.download(event(), context()));

        verify(commands).send("DOWNLOAD_COMPLETE", PLAN_KEY, DATASET);
        verifyNoMoreInteractions(commands);
    }

    @Test
    void doesNotPublishEventOnChecksumMismatch() throws Exception {
        var s3 = new FakeS3();
        s3.objects.put(PLAN_KEY, fixtureBytes("4b845f1a-b0dc-33f1-b76b-b76bb7cd0c56.json"));
        var os = mock(OsClient.class);
        when(os.download(anyString())).thenReturn(new ByteArrayInputStream("wrong content".getBytes(StandardCharsets.UTF_8)));
        var commands = mock(Commands.class);
        var service = new DownloadService(s3.client(), os, commands, properties());

        assertEquals("CHECKSUM_MISMATCH", service.download(event(), context()));

        verifyNoInteractions(commands);
    }

    @Test
    void doesNotPublishEventWhenReceiptAlreadyExists() throws Exception {
        var s3 = new FakeS3();
        s3.objects.put(PLAN_KEY, fixtureBytes("4b845f1a-b0dc-33f1-b76b-b76bb7cd0c56.json"));
        s3.objects.put(ReleaseStore.receiptKey(RELEASE_ID, DATASET, "download"), "{}".getBytes(StandardCharsets.UTF_8));
        var os = mock(OsClient.class);
        var commands = mock(Commands.class);
        var service = new DownloadService(s3.client(), os, commands, properties());

        assertEquals("SKIPPED", service.download(event(), context()));

        verifyNoInteractions(os);
        verifyNoInteractions(commands);
    }

    @Test
    void eventPublishFailureFailsInvocationAndRetryIsSkippedWithoutRepublishing() throws Exception {
        byte[] zip = "retry zip bytes".getBytes(StandardCharsets.UTF_8);
        var s3 = new FakeS3();
        s3.objects.put(PLAN_KEY, JSON.writeValueAsBytes(manifestWithSelectedMd5(md5(zip))));
        var os = mock(OsClient.class);
        when(os.download(anyString())).thenReturn(new ByteArrayInputStream(zip));
        var commands = mock(Commands.class);
        doThrow(new IllegalArgumentException("EventBridge rejected command"))
                .doNothing()
                .when(commands)
                .send(anyString(), anyString(), anyString());
        var service = new DownloadService(s3.client(), os, commands, properties());

        assertThrows(IllegalArgumentException.class, () -> service.download(event(), context()));
        assertTrue(s3.objects.containsKey(ReleaseStore.receiptKey(RELEASE_ID, DATASET, "download")));

        assertEquals("SKIPPED", service.download(event(), context()));

        verify(commands, times(1)).send("DOWNLOAD_COMPLETE", PLAN_KEY, DATASET);
        verify(os, times(1)).download(anyString());
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
                .downloadReceipt(BUCKET, RELEASE_ID, DATASET, DATASET + ".zip", "md5", "https://api.os.uk/x", java.time.Instant.now(), upload);

        assertArrayEquals(original, s3.objects.get(receiptKey));
    }

    private static void assertRejected(Map<String, Object> event, String expectedMessage) {
        var s3 = new FakeS3();
        var os = mock(OsClient.class);
        var commands = mock(Commands.class);
        var service = new DownloadService(s3.client(), os, commands, properties());

        var failure = assertThrows(IllegalArgumentException.class, () -> service.download(event, context()));

        assertEquals(expectedMessage, failure.getMessage());
        assertTrue(s3.objects.isEmpty());
        assertTrue(s3.uploads.isEmpty());
        verifyNoInteractions(os);
        verifyNoInteractions(commands);
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
            var service = new DownloadService(s3.client(), os, mock(Commands.class), properties());

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
        var service = new DownloadService(s3.client(), os, mock(Commands.class), properties());

        assertEquals("CHECKSUM_MISMATCH", service.download(event(), context()));

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
        var service = new DownloadService(s3.client(), os, mock(Commands.class), properties());

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

    private static final class FakeS3 {
        final Map<String, byte[]> objects = new LinkedHashMap<>();
        final Map<String, Upload> uploads = new HashMap<>();
        final List<String> completedKeys = new ArrayList<>();
        int uploadCounter;
        int abortedUploads;

        S3Client client() {
            return (S3Client)
                    Proxy.newProxyInstance(
                            S3Client.class.getClassLoader(),
                            new Class<?>[]{S3Client.class},
                            (proxy, method, args) -> invoke(method.getName(), args));
        }

        private Object invoke(String method, Object[] args) throws Exception {
            return switch (method) {
                case "serviceName" -> "s3";
                case "close" -> null;
                case "listObjectsV2" -> listObjects((ListObjectsV2Request) args[0]);
                case "getObject" -> getObject((GetObjectRequest) args[0]);
                case "createMultipartUpload" -> createMultipartUpload(createMultipartUploadRequest(args[0]));
                case "uploadPart" -> uploadPart(uploadPartRequest(args[0]), (RequestBody) args[1]);
                case "completeMultipartUpload" -> completeMultipartUpload(completeMultipartUploadRequest(args[0]));
                case "abortMultipartUpload" -> abortMultipartUpload(abortMultipartUploadRequest(args[0]));
                case "putObject" -> putObject(putObjectRequest(args[0]), (RequestBody) args[1]);
                default -> throw new UnsupportedOperationException(method);
            };
        }

        private ListObjectsV2Response listObjects(ListObjectsV2Request request) {
            var matches =
                    objects.keySet().stream()
                            .filter(k -> k.startsWith(request.prefix()))
                            .map(k -> S3Object.builder().key(k).build())
                            .toList();
            return ListObjectsV2Response.builder().contents(matches).build();
        }

        private ResponseInputStream<GetObjectResponse> getObject(GetObjectRequest request) {
            byte[] bytes = objects.get(request.key());
            if (bytes == null)
                throw S3Exception.builder().statusCode(404).message("Not found").build();
            return new ResponseInputStream<>(
                    GetObjectResponse.builder().build(),
                    AbortableInputStream.create(new ByteArrayInputStream(bytes)));
        }

        private CreateMultipartUploadResponse createMultipartUpload(CreateMultipartUploadRequest request) {
            String uploadId = "upload-" + (++uploadCounter);
            uploads.put(uploadId, new Upload(request.key()));
            return CreateMultipartUploadResponse.builder().uploadId(uploadId).build();
        }

        private UploadPartResponse uploadPart(UploadPartRequest request, RequestBody body) throws Exception {
            uploads.get(request.uploadId()).parts.put(request.partNumber(), read(body));
            return UploadPartResponse.builder().eTag("etag-" + request.partNumber()).build();
        }

        private CompleteMultipartUploadResponse completeMultipartUpload(
                CompleteMultipartUploadRequest request) {
            Upload upload = uploads.remove(request.uploadId());
            byte[] combined =
                    upload.parts.values().stream()
                            .reduce(
                                    new byte[0],
                                    (a, b) -> {
                                        byte[] out = Arrays.copyOf(a, a.length + b.length);
                                        System.arraycopy(b, 0, out, a.length, b.length);
                                        return out;
                                    });
            objects.put(upload.key, combined);
            completedKeys.add(upload.key);
            return CompleteMultipartUploadResponse.builder().versionId("version-" + completedKeys.size()).build();
        }

        private AbortMultipartUploadResponse abortMultipartUpload(AbortMultipartUploadRequest request) {
            uploads.remove(request.uploadId());
            abortedUploads++;
            return AbortMultipartUploadResponse.builder().build();
        }

        private PutObjectResponse putObject(PutObjectRequest request, RequestBody body) throws Exception {
            if ("*".equals(request.ifNoneMatch()) && objects.containsKey(request.key()))
                throw S3Exception.builder().statusCode(412).message("exists").build();
            objects.put(request.key(), read(body));
            return PutObjectResponse.builder().versionId("put-version").build();
        }

        private static byte[] read(RequestBody body) throws Exception {
            try (var in = body.contentStreamProvider().newStream()) {
                return in.readAllBytes();
            }
        }

        @SuppressWarnings("unchecked")
        private static CreateMultipartUploadRequest createMultipartUploadRequest(Object arg) {
            if (arg instanceof CreateMultipartUploadRequest request) return request;
            var builder = CreateMultipartUploadRequest.builder();
            ((Consumer<CreateMultipartUploadRequest.Builder>) arg).accept(builder);
            return builder.build();
        }

        @SuppressWarnings("unchecked")
        private static UploadPartRequest uploadPartRequest(Object arg) {
            if (arg instanceof UploadPartRequest request) return request;
            var builder = UploadPartRequest.builder();
            ((Consumer<UploadPartRequest.Builder>) arg).accept(builder);
            return builder.build();
        }

        @SuppressWarnings("unchecked")
        private static CompleteMultipartUploadRequest completeMultipartUploadRequest(Object arg) {
            if (arg instanceof CompleteMultipartUploadRequest request) return request;
            var builder = CompleteMultipartUploadRequest.builder();
            ((Consumer<CompleteMultipartUploadRequest.Builder>) arg).accept(builder);
            return builder.build();
        }

        @SuppressWarnings("unchecked")
        private static AbortMultipartUploadRequest abortMultipartUploadRequest(Object arg) {
            if (arg instanceof AbortMultipartUploadRequest request) return request;
            var builder = AbortMultipartUploadRequest.builder();
            ((Consumer<AbortMultipartUploadRequest.Builder>) arg).accept(builder);
            return builder.build();
        }

        @SuppressWarnings("unchecked")
        private static PutObjectRequest putObjectRequest(Object arg) {
            if (arg instanceof PutObjectRequest request) return request;
            var builder = PutObjectRequest.builder();
            ((Consumer<PutObjectRequest.Builder>) arg).accept(builder);
            return builder.build();
        }

        private record Upload(String key, Map<Integer, byte[]> parts) {
            Upload(String key) {
                this(key, new TreeMap<>());
            }
        }
    }

}
