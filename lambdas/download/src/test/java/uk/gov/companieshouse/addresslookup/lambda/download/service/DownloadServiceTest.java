package uk.gov.companieshouse.addresslookup.lambda.download.service;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.mockConstruction;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;
import static uk.gov.companieshouse.addresslookup.lambda.shared.runtime.RuntimeSupport.JSON;

import com.amazonaws.services.lambda.runtime.Context;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Duration;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HexFormat;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.MethodSource;
import org.junit.jupiter.params.provider.ValueSource;
import org.mockito.Mock;
import org.mockito.MockedConstruction;
import org.mockito.junit.jupiter.MockitoExtension;
import software.amazon.awssdk.services.s3.model.S3Exception;
import uk.gov.companieshouse.addresslookup.lambda.shared.acquisition.AcquisitionProperties;
import uk.gov.companieshouse.addresslookup.lambda.shared.exception.AbstractLambdaFailureException;
import uk.gov.companieshouse.addresslookup.lambda.shared.exception.PermanentFailureException;
import uk.gov.companieshouse.addresslookup.lambda.shared.exception.TransientFailureException;
import uk.gov.companieshouse.addresslookup.lambda.shared.os.OsClient;
import uk.gov.companieshouse.addresslookup.lambda.shared.storage.StreamingS3;
import uk.gov.companieshouse.addresslookup.lambda.shared.testsupport.FakeS3;
import uk.gov.companieshouse.addresslookup.lambda.shared.util.KeyLayout;
import uk.gov.companieshouse.release.model.Dataset;
import uk.gov.companieshouse.release.model.DatasetDefault;

@ExtendWith(MockitoExtension.class)
final class DownloadServiceTest {

    private static final String BUCKET = "landing-bucket";
    private static final String PLAN_KEY =
            "initial_full/12345/159159/manifest/data_package_manifest.json";

    private static final String RELEASE_ID = "6d5dccf3-5b0f-3134-b244-e0bdb67938ab";

    private static final String TEST_MANIFEST_FILE_NAME = "download-test-manifest.json";
    private static final String DATASET = "add_gb_royalmailaddress";
    private static final List<String> DATASETS = List.of( "add_gb_royalmailaddress",
            "add_isl_royalmailaddress");
    private static final String TEST_ZIP_BYTES_STRING = "local test zip bytes";

    @Mock private OsClient os;

    @Mock private Context context;

    @Test
    void downloadsOnlyTheDatasetNamedByTheEventBridgeCommand() throws Exception {
        byte[] zip = TEST_ZIP_BYTES_STRING.getBytes(StandardCharsets.UTF_8);
        JsonNode manifest = manifestWithSelectedMd5(md5(zip));
        Map<String, Object> event = Map.of("manifest", JSON.writeValueAsString(manifest));
        var s3 = new FakeS3();
        s3.objects.put(PLAN_KEY, JSON.writeValueAsBytes(manifest));
        List<String> expectedUrls = List.of(manifest.path(DATASETS.get(0)).path("zip").path("url").asText(),
                manifest.path(DATASETS.get(1)).path("zip").path("url").asText());

        when(os.download(expectedUrls.get(0))).thenReturn(new ByteArrayInputStream(zip));
        when(os.download(expectedUrls.get(1))).thenReturn(new ByteArrayInputStream(zip));
        when(context.getRemainingTimeInMillis()).thenReturn(900000);

        var service = new DownloadService(s3.client(), os, properties());
        assertEquals("ZIP_SCAN,ZIP_SCAN", service.download(event, context));

        List<String> expectedZipKeys = List.of("initial_full/12345/159159/downloads/" + DATASETS.get(0) + ".zip",
                "initial_full/12345/159159/downloads/" + DATASETS.get(1) + ".zip");
        verify(os).download(expectedUrls.get(0));
        verify(os).download(expectedUrls.get(1));
        assertEquals(expectedZipKeys, s3.completedKeys);
        var extractionInput = KeyLayout.parseLandingObject(s3.completedKeys.getFirst());
        assertEquals(KeyLayout.Feed.INITIAL_FULL, extractionInput.packageVersion().feed());
        assertEquals("12345", extractionInput.packageVersion().packageId());
        assertEquals("159159", extractionInput.packageVersion().versionId());
        assertEquals(Dataset.named(DATASET), extractionInput.dataset());
        assertEquals(KeyLayout.LandingFileType.ZIP, extractionInput.fileType());
        assertTrue(s3.completedKeys.stream().noneMatch(k -> k.contains("add_gb_builtaddress")));
        assertTrue(s3.objects.keySet().stream().noneMatch(k -> k.endsWith(".receipt.json")));
        String checksumKey =
                "metadata/checksums/"
                        + sha256(expectedZipKeys.getFirst().getBytes(StandardCharsets.UTF_8))
                        + ".json";
        JsonNode evidence = JSON.readTree(s3.objects.get(checksumKey));
        assertEquals(BUCKET, evidence.path("bucket").asText());
        assertEquals(expectedZipKeys.getFirst(), evidence.path("key").asText());
        assertEquals(md5(zip), evidence.path("md5").asText());
        assertEquals(sha256(zip), evidence.path("sha256").asText());
        assertEquals(zip.length, evidence.path("bytes").asLong());
        assertFalse(evidence.path("versionId").asText().isBlank());
        assertTrue(
                s3.objects.keySet().stream().noneMatch(k -> k.endsWith(".download-attempt.json")));
    }

    @Test
    void rejectsNullEvent() {
        assertRejected(null, "Missing download event");
    }

    @Test
    void rejectsEventWithoutManifest() {
        Map<String, Object> event = new HashMap<>();
        event.put("manifest", null);

        assertRejected(event, "Missing manifest entry");
    }

    @Test
    void rejectsCommandWithInvalidManifest() {
        Map<String, Object> event = new HashMap<>();
        event.put("manifest", "Not a manifest");

        assertRejected(event, "Invalid manifest entry");
    }

    @Test
    void doesNotPublishDownloadCompleteEventAfterSuccessfulDownload() throws Exception {
        byte[] zip = TEST_ZIP_BYTES_STRING.getBytes(StandardCharsets.UTF_8);
        var s3 = new FakeS3();
        s3.objects.put(PLAN_KEY, JSON.writeValueAsBytes(manifestWithSelectedMd5(md5(zip))));
        when(os.download(anyString())).thenReturn(new ByteArrayInputStream(zip))
                .thenReturn(new ByteArrayInputStream(zip));
        when(context.getRemainingTimeInMillis()).thenReturn(900000);

        var service = new DownloadService(s3.client(), os, properties());

        assertEquals("ZIP_SCAN,ZIP_SCAN", service.download(event(), context));

        assertEquals(2, s3.completedKeys.size());
    }

    @Test
    void doesNotPublishEventOnChecksumMismatch() throws Exception {
        var s3 = new FakeS3();
        s3.objects.put(PLAN_KEY, fixtureBytes());
        when(os.download(anyString()))
                .thenReturn(
                        new ByteArrayInputStream("wrong content".getBytes(StandardCharsets.UTF_8)));
        when(context.getRemainingTimeInMillis()).thenReturn(900000);

        var service = new DownloadService(s3.client(), os, properties());

        Exception exception =
                assertThrows(
                        TransientFailureException.class, () -> service.download(event(), context));
        assertTrue(
                exception
                        .getMessage()
                        .startsWith("Description [Download failure, checksum mismatch:"));
    }

    private void assertRejected(Map<String, Object> event, String expectedMessage) {
        var s3 = new FakeS3();
        var service = new DownloadService(s3.client(), os, properties());

        var failure =
                assertThrows(
                        IllegalArgumentException.class, () -> service.download(event, context));

        assertEquals(expectedMessage, failure.getMessage());
        assertTrue(s3.objects.isEmpty());
        assertTrue(s3.uploads.isEmpty());
        verifyNoInteractions(os);
    }

    @Test
    void checksumMismatchAbortsUploadAndDoesNotPublishReceipt() throws Exception {
        var s3 = new FakeS3();
        s3.objects.put(PLAN_KEY, fixtureBytes());
        when(os.download(anyString()))
                .thenReturn(
                        new ByteArrayInputStream("wrong content".getBytes(StandardCharsets.UTF_8)));
        var service = new DownloadService(s3.client(), os, properties());
        var downloadEvent = event();
        when(context.getRemainingTimeInMillis()).thenReturn(900000);

        var failure =
                assertThrows(
                        TransientFailureException.class,
                        () -> service.download(downloadEvent, context));
        String failureMessage = failure.getMessage();
        assertTrue(
                failureMessage.contains("expected=") && failureMessage.contains("actual="),
                "mismatch must include both MD5 values");
        assertTrue(
                failureMessage.contains(
                        "s3Key=initial_full/12345/159159/downloads/" + DATASET + ".zip"),
                "mismatch must include the S3 key");
        assertEquals("CHECKSUM_MISMATCH", failure.reasonCode());
        assertEquals(RELEASE_ID, failure.releaseId());
        assertEquals(Dataset.named(DATASET), failure.dataset());
        assertTrue(s3.abortedUploads > 0, "multipart upload should be aborted");
        assertTrue(s3.objects.keySet().stream().noneMatch(k -> k.endsWith(".receipt.json")));
        assertTrue(s3.completedKeys.isEmpty(), "checksum mismatch must not complete a ZIP object");
        assertTrue(
                s3.objects.keySet().stream().noneMatch(k -> k.endsWith(".download-attempt.json")));
    }

    @ParameterizedTest
    @ValueSource(classes = {IOException.class, InterruptedException.class})
    void shouldHandleExceptionsFromOsClientWithTransientFailureException(
            Class<? extends Exception> exceptionClass) throws Exception {

        final String EXCEPTION_MESSAGE = "exception message";
        Exception thrownException = mock(exceptionClass);
        when(thrownException.getMessage()).thenReturn(EXCEPTION_MESSAGE);
        var s3 = new FakeS3();

        s3.objects.put(PLAN_KEY, fixtureBytes());

        when(os.download(anyString())).thenThrow(thrownException);
        var service = new DownloadService(s3.client(), os, properties());

        try {
            Exception exception =
                    assertThrows(
                            TransientFailureException.class,
                            () -> service.download(event(), context));
            assertTrue(exception.getMessage().startsWith("Description [exception message]"));
            assertInstanceOf(exceptionClass, exception.getCause());
            assertEquals(
                    exceptionClass == InterruptedException.class,
                    Thread.currentThread().isInterrupted());
        } finally {
            Thread.interrupted();
        }
    }

    @ParameterizedTest
    @MethodSource("getTestExceptionClassAndExpectedDescription")
    void shouldHandleExceptionsFromStreamingS3Put(ClassAndDescription classAndDescription)
            throws Exception {
        final String EXCEPTION_MESSAGE = "exception message";

        String dataset = "add_gb_royalmailaddress";

        byte[] zip = ("zip for add_gb_royalmailaddress").getBytes(StandardCharsets.UTF_8);

        var manifest = manifestWithMd5(dataset, md5(zip));

        var s3 = new FakeS3();
        s3.objects.put(PLAN_KEY, JSON.writeValueAsBytes(manifest));

        Map<String, Object> event = event();

        when(os.download(anyString())).thenReturn(new ByteArrayInputStream(zip))
                .thenReturn(new ByteArrayInputStream(zip));

        class TheCauseException extends Exception {
            TheCauseException(String message) {
                super(message);
            }
        }
        TheCauseException causeException = new TheCauseException(EXCEPTION_MESSAGE);

        Exception exceptionToThrow = mock(classAndDescription.exceptionClass());
        if (exceptionToThrow instanceof StreamingS3.ExistingObjectVerificationException) {
            when(exceptionToThrow.getCause()).thenReturn(causeException);
        }

        try (MockedConstruction<StreamingS3> ignored =
                mockConstruction(
                        StreamingS3.class,
                        (mock, ignore) ->
                                when(mock.putOverwrite(
                                                any(InputStream.class),
                                                anyString(),
                                                anyString(),
                                                anyString(),
                                                anyLong()))
                                        .thenThrow(exceptionToThrow))) {
            var service = new DownloadService(s3.client(), os, properties());

            Class<? extends AbstractLambdaFailureException> expected =
                    exceptionToThrow instanceof StreamingS3.ChecksumMismatchException
                            ? TransientFailureException.class
                            : PermanentFailureException.class;
            Exception exception = assertThrows(expected, () -> service.download(event, context));

            if (exceptionToThrow instanceof StreamingS3.ChecksumMismatchException) {
                assertEquals(exceptionToThrow, exception.getCause());
            } else {
                assertInstanceOf(TheCauseException.class, exception.getCause());
            }
            assertTrue(
                    exception.getMessage().startsWith(classAndDescription.expectedDescription()));
        }
    }

    private record ClassAndDescription(
            Class<? extends Exception> exceptionClass, String expectedDescription) {}

    private static List<ClassAndDescription> getTestExceptionClassAndExpectedDescription() {
        return List.of(
                new ClassAndDescription(
                        StreamingS3.ChecksumMismatchException.class,
                        "Description [Download failure, checksum mismatch:"),
                new ClassAndDescription(
                        StreamingS3.ExistingObjectVerificationException.class,
                        "Description [Error occurred whilst attempting to verify if the ZIP file"
                            + " has previously been stored in the S3 bucket.]"));
    }

    @ParameterizedTest
    @MethodSource("getCauseExceptionClassAndFinalThrownException")
    void shouldHandleExistingObjectVerificationExceptionWithDifferentCauses(
            ExceptionAndExceptionClass testData) throws Exception {

        String dataset = "add_gb_royalmailaddress";

        byte[] zip = ("zip for add_gb_royalmailaddress").getBytes(StandardCharsets.UTF_8);

        var manifest = manifestWithMd5(dataset, md5(zip));

        var s3 = new FakeS3();
        s3.objects.put(PLAN_KEY, JSON.writeValueAsBytes(manifest));

        Map<String, Object> event = event();

        when(os.download(anyString())).thenReturn(new ByteArrayInputStream(zip));

        try (MockedConstruction<StreamingS3> ignored =
                mockConstruction(
                        StreamingS3.class,
                        (mock, ignore) ->
                                when(mock.putOverwrite(
                                                any(InputStream.class),
                                                anyString(),
                                                anyString(),
                                                anyString(),
                                                anyLong()))
                                        .thenThrow(
                                                new StreamingS3.ExistingObjectVerificationException(
                                                        testData.exception())))) {
            var service = new DownloadService(s3.client(), os, properties());

            Exception exception =
                    assertThrows(
                            testData.finalThrownExceptionClass(),
                            () -> service.download(event, context));

            assertInstanceOf(testData.exception().getClass(), exception.getCause());

            assertTrue(
                    exception
                            .getMessage()
                            .startsWith(
                                    "Description [Error occurred whilst attempting to verify if the"
                                        + " ZIP file has previously been stored in the S3"
                                        + " bucket.]"));
        }
    }

    private record ExceptionAndExceptionClass(
            Exception exception,
            Class<? extends AbstractLambdaFailureException> finalThrownExceptionClass) {}

    private static List<ExceptionAndExceptionClass>
            getCauseExceptionClassAndFinalThrownException() {
        List<ExceptionAndExceptionClass> exceptions = new ArrayList<>();
        exceptions.add(
                new ExceptionAndExceptionClass(new IOException(), TransientFailureException.class));

        S3Exception s3Exception = (S3Exception) (S3Exception.builder().statusCode(404).build());
        exceptions.add(
                new ExceptionAndExceptionClass(s3Exception, PermanentFailureException.class));

        s3Exception = (S3Exception) (S3Exception.builder().statusCode(501).build());
        exceptions.add(
                new ExceptionAndExceptionClass(s3Exception, TransientFailureException.class));

        return exceptions;
    }

    @Test
    void unreadableChecksumEvidenceFailsAsTransient() throws Exception {
        var s3 = new FakeS3();
        s3.objects.put(PLAN_KEY, fixtureBytes());
        s3.objects.put(
                KeyLayout.checksumKey("initial_full/12345/159159/downloads/" + DATASET + ".zip"),
                "{not json".getBytes(StandardCharsets.UTF_8));
        var service = new DownloadService(s3.client(), os, properties());
        var downloadEvent = event();

        var failure =
                assertThrows(
                        TransientFailureException.class,
                        () -> service.download(downloadEvent, context));

        assertEquals("FAILED_TO_READ_CHECKSUM_EVIDENCE", failure.reasonCode());
        assertEquals(Dataset.named(DATASET), failure.dataset());
        assertInstanceOf(IOException.class, failure.getCause());
        verifyNoInteractions(os);
    }

    @Test
    void missingDigestAlgorithmFailsAsPermanentUnrecognisedDigestScheme() throws Exception {
        var s3 = new FakeS3();
        s3.objects.put(PLAN_KEY, fixtureBytes());
        when(os.download(anyString()))
                .thenReturn(new ByteArrayInputStream("zip bytes".getBytes(StandardCharsets.UTF_8)));
        var downloadEvent = event();
        var digestFailure = new NoSuchAlgorithmException("MD5 not available");

        try (MockedConstruction<StreamingS3> ignored =
                mockConstruction(
                        StreamingS3.class,
                        (mock, ignore) ->
                                when(mock.putOverwrite(
                                                any(InputStream.class),
                                                anyString(),
                                                anyString(),
                                                anyString(),
                                                anyLong()))
                                        .thenThrow(digestFailure))) {
            var service = new DownloadService(s3.client(), os, properties());

            var failure =
                    assertThrows(
                            PermanentFailureException.class,
                            () -> service.download(downloadEvent, context));

            assertEquals("UNRECOGNISED_DIGEST_SCHEME", failure.reasonCode());
            assertEquals(RELEASE_ID, failure.releaseId());
            assertEquals(Dataset.named(DATASET), failure.dataset());
            assertSame(digestFailure, failure.getCause());
        }
        assertTrue(s3.objects.keySet().stream().noneMatch(key -> key.endsWith(".receipt.json")));
    }

    @Test
    void repeatedDownloadSkipsCurrentVersionWithMatchingEvidence() throws Exception {
        var s3 = new FakeS3();
        byte[] zip = "repeat download".getBytes(StandardCharsets.UTF_8);
        JsonNode manifest = manifestWithMd5ForAllDatasets(md5(zip));
        Map<String, Object> event =  Map.of("manifest", JSON.writeValueAsString(manifest));
        List<String> expectedZipKeys = List.of("initial_full/12345/159159/downloads/" + DATASETS.get(0) + ".zip",
                "initial_full/12345/159159/downloads/" + DATASETS.get(1) + ".zip");s3.objects.put(PLAN_KEY, JSON.writeValueAsBytes(manifestWithSelectedMd5(md5(zip))));

        when(os.download(anyString())).thenReturn(new ByteArrayInputStream(zip))
                .thenReturn(new ByteArrayInputStream(zip));
        var service = new DownloadService(s3.client(), os, properties());

        when(context.getRemainingTimeInMillis()).thenReturn(900000);
        assertEquals("ZIP_SCAN,ZIP_SCAN", service.download(event, context));
        assertEquals("SKIPPED,SKIPPED", service.download(event, context));
        assertEquals(expectedZipKeys, s3.completedKeys);
        verify(os, times(2)).download(anyString());
        assertTrue(s3.objects.keySet().stream().noneMatch(k -> k.endsWith(".receipt.json")));
    }

    @ParameterizedTest
    @ValueSource(strings = {"missing", "replaced", "md5"})
    void downloadsAgainWhenExistingZipEvidenceIsStale(String reason) throws Exception {
        var s3 = new FakeS3();
        byte[] zip = TEST_ZIP_BYTES_STRING.getBytes(StandardCharsets.UTF_8);
        List<String> expectedZipKeys = List.of("initial_full/12345/159159/downloads/" + DATASETS.get(0) + ".zip",
                "initial_full/12345/159159/downloads/" + DATASETS.get(1) + ".zip");
        s3.objects.put(PLAN_KEY, JSON.writeValueAsBytes(manifestWithSelectedMd5(md5(zip))));
        when(os.download(anyString())).thenAnswer(call -> new ByteArrayInputStream(zip))
                .thenAnswer(call -> new ByteArrayInputStream(zip));
        when(context.getRemainingTimeInMillis()).thenReturn(900000);
        var service = new DownloadService(s3.client(), os, properties());
        assertEquals("ZIP_SCAN,ZIP_SCAN", service.download(event(), context));
        assertEquals(expectedZipKeys, s3.completedKeys);

        switch (reason) {
            case "missing" -> s3.objects.remove(expectedZipKeys.get(1));
            case "replaced" -> s3.versions.put(expectedZipKeys.get(1), "replacement-version");
            case "md5" -> {
                String evidenceKey = KeyLayout.checksumKey(expectedZipKeys.get(1));
                var evidence = (ObjectNode) JSON.readTree(s3.objects.get(evidenceKey));
                evidence.put("md5", "0".repeat(32));
                s3.objects.put(evidenceKey, JSON.writeValueAsBytes(evidence));
            }
            default -> throw new IllegalArgumentException(reason);
        }

        assertEquals("SKIPPED,ZIP_SCAN", service.download(event(), context));
        // The second dataset will have been downloaded again due to the change made above.
        List<String> newExpectedZipKeys = List.of(expectedZipKeys.get(0),
                expectedZipKeys.get(1),  expectedZipKeys.get(1));
        assertEquals(newExpectedZipKeys, s3.completedKeys);
        assertArrayEquals(zip, s3.objects.get(expectedZipKeys.get(0)));
    }

    private static AcquisitionProperties properties() {
        return new AcquisitionProperties(
                "control-bucket",
                BUCKET,
                "event-bus",
                "redacted",
                "{}",
                512L * 1024L * 1024L,
                List.of(new DatasetDefault("add_gb_royalmailaddress", "2.0"),
                        new DatasetDefault("add_isl_royalmailaddress", "2.0")),
                null,
                1024L * 1024L * 1024L,
                20,
                Duration.ofSeconds(20),
                Duration.ofSeconds(840));
    }

    private static Map<String, Object> event() throws Exception {
        byte[] zip = TEST_ZIP_BYTES_STRING.getBytes(StandardCharsets.UTF_8);
        JsonNode manifest = manifestWithSelectedMd5(md5(zip));
        return Map.of("manifest", JSON.writeValueAsString(manifest));
    }

    private static JsonNode manifestWithSelectedMd5(String md5) throws Exception {
        return manifestWithMd5(DATASET, md5);
    }

    private static JsonNode manifestWithMd5(String dataset, String md5) throws Exception {
        ObjectNode manifest =
                (ObjectNode)
                        JSON.readTree(fixtureBytes());
        ((ObjectNode) manifest.path(dataset).path("zip")).put("md5", md5);
        return manifest;
    }

    private static JsonNode manifestWithMd5ForAllDatasets(String md5) throws Exception {
        ObjectNode manifest =
                (ObjectNode)
                        JSON.readTree(fixtureBytes());
        for (String dataset: DATASETS)
            ((ObjectNode) manifest.path(dataset).path("zip")).put("md5", md5);
        return manifest;
    }

    private static byte[] fixtureBytes() throws Exception {
        try (var in =
                DownloadServiceTest.class.getResourceAsStream("/example-download-lambda/" + DownloadServiceTest.TEST_MANIFEST_FILE_NAME)) {
            assertNotNull(in, "Missing fixture " + DownloadServiceTest.TEST_MANIFEST_FILE_NAME);
            return in.readAllBytes();
        }
    }

    private static String md5(byte[] bytes) throws Exception {
        return HexFormat.of().formatHex(MessageDigest.getInstance("MD5").digest(bytes));
    }

    private static String sha256(byte[] bytes) throws Exception {
        return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(bytes));
    }
}
