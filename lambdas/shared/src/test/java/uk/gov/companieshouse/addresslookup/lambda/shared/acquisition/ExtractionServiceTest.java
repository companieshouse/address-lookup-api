package uk.gov.companieshouse.addresslookup.lambda.shared.acquisition;

import com.amazonaws.services.lambda.runtime.Context;
import org.junit.jupiter.api.Test;
import uk.gov.companieshouse.addresslookup.lambda.shared.storage.ReleaseStore;
import uk.gov.companieshouse.addresslookup.lambda.shared.testsupport.FakeS3;
import uk.gov.companieshouse.addresslookup.lambda.shared.testsupport.TestContext;
import uk.gov.companieshouse.release.model.Dataset;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;

import static org.junit.jupiter.api.Assertions.*;
import static uk.gov.companieshouse.addresslookup.lambda.shared.runtime.RuntimeSupport.JSON;

final class ExtractionServiceTest {
    private static final String BUCKET = "source-bucket";
    private static final String SCANNED_BUCKET = "scanned-bucket";
    private static final String PLAN_KEY = "acquisitions/2026-05-16/4b845f1a-b0dc-33f1-b76b-b76bb7cd0c56.json";
    private static final String RELEASE_ID = "4b845f1a-b0dc-33f1-b76b-b76bb7cd0c56";
    private static final String DATASET = "add_gb_royalmailaddress";
    private static final String ZIP_KEY = "Scanned/" + RELEASE_ID + "/" + DATASET + ".zip";
    private static final long PLENTY_OF_TIME = 900_000;

    private final FakeS3 s3 = new FakeS3();

    @Test
    void extractsPrimaryCsvAndRecordsReceipt() throws Exception {
        String csv = header() + "\nrow-1\nrow-2\n";
        stageCleanZip(zip(entries(DATASET + ".csv", csv, DATASET + "_extra.csv", "a,b\n1,2\n")));

        assertEquals("CSV_SCAN", service(1024 * 1024).unzip(event(), context(PLENTY_OF_TIME)));

        assertEquals(1, s3.completedKeys.size());
        String csvKey = s3.completedKeys.get(0);
        assertTrue(csvKey.startsWith("quarantine/csv/" + RELEASE_ID + "/" + DATASET + "/"));
        assertTrue(csvKey.endsWith(".csv"));
        assertEquals(csv, new String(s3.objects.get(csvKey), StandardCharsets.UTF_8));
        var receipt = JSON.readTree(s3.objects.get(ReleaseStore.receiptKey(RELEASE_ID, DATASET, "extract")));
        assertEquals(csvKey, receipt.path("key").asText());
    }

    @Test
    void usesTheVersionStoredInTheDownloadReceiptWhenPresent() throws Exception {
        stageCleanZip(zip(entries(DATASET + ".csv", header() + "\nrow\n")), JSON.writeValueAsBytes(
                Map.of("s3Key", ZIP_KEY, "version", FakeS3.DEFAULT_VERSION)));

        assertEquals("CSV_SCAN", service(1024 * 1024).unzip(event(), context(PLENTY_OF_TIME)));
    }

    @Test
    void skipsWhenExtractReceiptAlreadyExists() throws Exception {
        s3.objects.put(PLAN_KEY, plan());
        s3.objects.put(ReleaseStore.receiptKey(RELEASE_ID, DATASET, "extract"), "{}".getBytes(StandardCharsets.UTF_8));

        assertEquals("SKIPPED", service(1024).unzip(event(), context(PLENTY_OF_TIME)));
        assertTrue(s3.completedKeys.isEmpty());
    }

    @Test
    void waitsWhenNoDownloadReceiptExists() throws Exception {
        s3.objects.put(PLAN_KEY, plan());

        assertEquals("WAITING_FOR_ZIP_SCAN", service(1024).unzip(event(), context(PLENTY_OF_TIME)));
    }

    @Test
    void waitsWhileZipScanIsPending() throws Exception {
        stageCleanZip(zip(entries(DATASET + ".csv", header() + "\n")));
        s3.scanStatus.clear();

        assertEquals("WAITING_FOR_ZIP_SCAN", service(1024).unzip(event(), context(PLENTY_OF_TIME)));
        assertTrue(s3.completedKeys.isEmpty());
    }

    @Test
    void rejectsMissingPlan() {
        var failure = assertThrows(
                IllegalArgumentException.class, () -> service(1024).unzip(event(), context(PLENTY_OF_TIME)));

        assertEquals("Missing acquisition plan", failure.getMessage());
    }

    @Test
    void rejectsPlanKeyOutsideAcquisitionsPrefix() {
        var event = Map.<String, Object>of("detail", Map.of("planKey", "other/plan.json", "dataset", DATASET));

        var failure = assertThrows(
                IllegalArgumentException.class, () -> service(1024).unzip(event, context(PLENTY_OF_TIME)));

        assertEquals("Invalid plan key", failure.getMessage());
    }

    @Test
    void rejectsCsvWhoseHeaderDoesNotMatchTheCatalogue() throws Exception {
        stageCleanZip(zip(entries(DATASET + ".csv", "not,the,right,header\nrow\n")));

        var failure = assertThrows(
                IllegalArgumentException.class, () -> service(1024 * 1024).unzip(event(), context(PLENTY_OF_TIME)));

        assertEquals("CSV header does not match pinned OS DDL", failure.getMessage());
        assertTrue(s3.completedKeys.isEmpty());
    }

    @Test
    void rejectsZipWithoutPrimaryCsv() throws Exception {
        stageCleanZip(zip(entries(DATASET + "_extra.csv", "a,b\n")));

        var failure = assertThrows(
                IllegalArgumentException.class, () -> service(1024 * 1024).unzip(event(), context(PLENTY_OF_TIME)));

        assertEquals("Missing primary CSV", failure.getMessage());
    }

    @Test
    void rejectsUnexpectedZipMembers() throws Exception {
        stageCleanZip(zip(entries("something_else.csv", "x\n")));

        var failure = assertThrows(
                IllegalArgumentException.class, () -> service(1024 * 1024).unzip(event(), context(PLENTY_OF_TIME)));

        assertEquals("Unexpected ZIP member", failure.getMessage());
    }

    @Test
    void rejectsZipMembersWithPaths() throws Exception {
        for (String name : new String[]{"nested/" + DATASET + ".csv", "..", "dir\\file.csv"}) {
            s3.objects.clear();
            stageCleanZip(zip(entries(name, "x\n")));

            var failure = assertThrows(
                    IllegalArgumentException.class, () -> service(1024 * 1024).unzip(event(), context(PLENTY_OF_TIME)));

            assertEquals("Unexpected ZIP member path", failure.getMessage(), name);
        }
    }

    @Test
    void rejectsAncillaryEntriesThatExceedTheExpandedSizeLimit() throws Exception {
        stageCleanZip(zip(entries(DATASET + "_big.csv", "x".repeat(200))));

        var failure = assertThrows(
                IllegalArgumentException.class, () -> service(100).unzip(event(), context(PLENTY_OF_TIME)));

        assertEquals("ZIP expanded size limit", failure.getMessage());
    }

    @Test
    void rejectsPrimaryCsvThatExceedsTheExpandedSizeLimit() throws Exception {
        stageCleanZip(zip(entries(DATASET + ".csv", header() + "\n" + "row\n".repeat(500))));

        var failure = assertThrows(IOException.class, () -> service(300).unzip(event(), context(PLENTY_OF_TIME)));

        assertEquals("Expanded archive limit", failure.getMessage());
        assertTrue(s3.completedKeys.isEmpty());
        assertTrue(s3.abortedUploads > 0);
    }

    @Test
    void failsWhenTheDeadlineIsApproachingWhileSkippingAncillaryEntries() throws Exception {
        stageCleanZip(zip(entries(DATASET + "_extra.csv", "a,b\n")));

        var failure = assertThrows(
                IllegalArgumentException.class, () -> service(1024 * 1024).unzip(event(), context(1_000)));

        assertEquals("Unzip deadline", failure.getMessage());
    }

    @Test
    void constructorRequiresScannedBucket() {
        var properties = new AcquisitionProperties(
                BUCKET, " ", "bus", "key", "{}", 1024, 1024, 20, Duration.ofSeconds(1), Duration.ofSeconds(1));

        var failure = assertThrows(
                IllegalArgumentException.class, () -> new ExtractionService(s3.client(), properties));

        assertEquals("Missing SCANNED_BUCKET", failure.getMessage());
    }

    @Test
    void countingInputCountsSingleByteAndBulkReads() throws Exception {
        var single = new ExtractionService.CountingInput(new ByteArrayInputStream(new byte[]{1, 2, 3}), 10);
        assertEquals(1, single.read());
        assertEquals(2, single.read());
        assertEquals(2, single.count);

        var bulk = new ExtractionService.CountingInput(new ByteArrayInputStream(new byte[4]), 10);
        assertEquals(4, bulk.read(new byte[8], 0, 8));
        assertEquals(-1, bulk.read(new byte[8], 0, 8));
        assertEquals(4, bulk.count);
    }

    @Test
    void countingInputFailsOnceTheLimitIsExceeded() {
        var in = new ExtractionService.CountingInput(new ByteArrayInputStream(new byte[]{1, 2, 3}), 2);

        assertDoesNotThrow(() -> in.read());
        assertDoesNotThrow(() -> in.read());
        var failure = assertThrows(IOException.class, in::read);

        assertEquals("Expanded archive limit", failure.getMessage());
    }

    private ExtractionService service(long maxExtractedBytes) {
        return new ExtractionService(
                s3.client(),
                new AcquisitionProperties(
                        BUCKET,
                        SCANNED_BUCKET,
                        "event-bus",
                        "redacted",
                        "{}",
                        512L * 1024L * 1024L,
                        maxExtractedBytes,
                        20,
                        Duration.ofSeconds(20),
                        Duration.ofSeconds(840)));
    }

    private void stageCleanZip(byte[] zip) throws Exception {
        stageCleanZip(zip, JSON.writeValueAsBytes(Map.of("s3Key", ZIP_KEY)));
    }

    private void stageCleanZip(byte[] zip, byte[] downloadReceipt) throws Exception {
        s3.objects.put(PLAN_KEY, plan());
        s3.objects.put(ReleaseStore.receiptKey(RELEASE_ID, DATASET, "download"), downloadReceipt);
        s3.objects.put(ZIP_KEY, zip);
        s3.scanStatus.put(ZIP_KEY, ReleaseStore.CLEAN);
    }

    private static Map<String, Object> event() {
        return Map.of("detail", Map.of("planKey", PLAN_KEY, "dataset", DATASET));
    }

    private static Context context(long remainingMillis) {
        return TestContext.withRemainingMillis(remainingMillis);
    }

    private static String header() throws Exception {
        return Dataset.ADD_GB_ROYALMAILADDRESS.table().header();
    }

    private static Map<String, String> entries(String... namesAndContents) {
        var map = new LinkedHashMap<String, String>();
        for (int i = 0; i < namesAndContents.length; i += 2) map.put(namesAndContents[i], namesAndContents[i + 1]);
        return map;
    }

    private static byte[] zip(Map<String, String> entries) throws IOException {
        var out = new ByteArrayOutputStream();
        try (var zip = new ZipOutputStream(out)) {
            for (var entry : entries.entrySet()) {
                zip.putNextEntry(new ZipEntry(entry.getKey()));
                zip.write(entry.getValue().getBytes(StandardCharsets.UTF_8));
                zip.closeEntry();
            }
        }
        return out.toByteArray();
    }

    private static byte[] plan() throws Exception {
        try (var in = ExtractionServiceTest.class.getResourceAsStream(
                "/example-download-lambda/4b845f1a-b0dc-33f1-b76b-b76bb7cd0c56.json")) {
            assertNotNull(in, "Missing plan fixture");
            return in.readAllBytes();
        }
    }
}

