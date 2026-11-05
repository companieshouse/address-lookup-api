package uk.gov.companieshouse.addresslookup.release.importer;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.mockStatic;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import static uk.gov.companieshouse.addresslookup.lambda.shared.runtime.RuntimeSupport.JSON;

import com.amazonaws.services.lambda.runtime.Context;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.MockedStatic;
import org.mockito.junit.jupiter.MockitoExtension;

import software.amazon.awssdk.services.s3.S3Client;
import software.amazon.awssdk.services.s3.model.ListObjectsV2Request;
import software.amazon.awssdk.services.s3.model.ListObjectsV2Response;

import uk.gov.companieshouse.addresslookup.lambda.shared.storage.ReconciliationCursor;
import uk.gov.companieshouse.addresslookup.lambda.shared.storage.ReleaseStore;
import uk.gov.companieshouse.addresslookup.release.runtime.ImportProperties;
import uk.gov.companieshouse.addresslookup.releasecore.service.DatasetImportService;
import uk.gov.companieshouse.addresslookup.releasecore.service.PromotionService;
import uk.gov.companieshouse.addresslookup.releasecore.service.ReleaseRegistration;
import uk.gov.companieshouse.addresslookup.releasecore.state.Release;
import uk.gov.companieshouse.addresslookup.releasecore.state.ReleaseRepository;
import uk.gov.companieshouse.release.model.Dataset;
import uk.gov.companieshouse.release.model.ObjectReference;
import uk.gov.companieshouse.release.model.ReleaseManifest;

import java.time.LocalDate;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

@ExtendWith(MockitoExtension.class)
class ImportServiceTest {
    private static final String BUCKET = "scanned";
    private static final String RELEASE_ID = "12345678-1234-1234-1234-123456789abc";
    private static final String MANIFEST_KEY = "manifests/2026-10-06/" + RELEASE_ID + ".json";
    private static final String OBJECT_KEY =
            "quarantine/csv/"
                    + RELEASE_ID
                    + "/add_gb_royalmailaddress/12345678-1234-1234-1234-123456789abc.csv";
    private static final String SHA256 = "a".repeat(64);

    @Mock private S3Client s3;
    @Mock private ImportProperties properties;
    @Mock private ReleaseRegistration registration;
    @Mock private DatasetImportService importer;
    @Mock private PromotionService promotion;
    @Mock private ReleaseRepository releases;
    @Mock private Context context;

    private ImportService service;

    @BeforeEach
    void setUp() {
        service = new ImportService(s3, properties, registration, importer, promotion, releases);
        lenient().when(properties.sweepLimit()).thenReturn(3);
        lenient()
                .when(releases.findTop20ByStatusOrderByValidToAsc(Release.Status.PROCESSING))
                .thenReturn(List.of());
        lenient().when(context.getRemainingTimeInMillis()).thenReturn(900_000);
    }

    @Test
    void reconcilesCleanManifestAndIgnoresMissingReadyFiles() throws Exception {
        var store = mock(ReleaseStore.class);
        var cursor = mock(ReconciliationCursor.class);
        var manifestNode = JSON.createObjectNode().put("manifest", true);
        var manifest = mock(ReleaseManifest.class);
        UUID id = UUID.fromString(RELEASE_ID);
        when(cursor.next()).thenReturn(Optional.of(MANIFEST_KEY), Optional.empty());
        when(releases.findById(id)).thenReturn(Optional.empty());
        when(store.isClean(BUCKET, MANIFEST_KEY, null)).thenReturn(true);
        when(store.read(BUCKET, MANIFEST_KEY)).thenReturn(manifestNode);
        when(manifest.id()).thenReturn(id);
        when(manifest.target()).thenReturn(LocalDate.parse("2026-10-06"));
        when(store.read(eq(BUCKET), org.mockito.ArgumentMatchers.startsWith("ready/")))
                .thenReturn(null);

        try (MockedStatic<ReleaseManifest> mockedManifest = mockStatic(ReleaseManifest.class)) {
            mockedManifest.when(() -> ReleaseManifest.read(manifestNode)).thenReturn(manifest);

            assertEquals(
                    "RECONCILED", service.reconcile(store, cursor, BUCKET, "eu-west-2", context));
        }

        verify(registration)
                .register(
                        eq(manifest),
                        eq(MANIFEST_KEY),
                        org.mockito.ArgumentMatchers.matches("[a-f0-9]{64}"));
        verify(store).isClean(BUCKET, MANIFEST_KEY, null);
        verify(importer, never()).importClean(any(), any(), any(), any(), any(), any(), any());
        verify(promotion, never()).promote(any());
    }

    @Test
    void stopsWhenThereAreNoManifests() throws Exception {
        var store = mock(ReleaseStore.class);
        var cursor = mock(ReconciliationCursor.class);
        when(cursor.next()).thenReturn(Optional.empty());

        assertEquals("RECONCILED", service.reconcile(store, cursor, BUCKET, "eu-west-2", context));

        verify(store, never()).read(any(), any());
        verify(registration, never()).register(any(), any(), any());
    }

    @Test
    void skipsCompletedManifestWithoutReadingIt() throws Exception {
        var store = mock(ReleaseStore.class);
        var cursor = mock(ReconciliationCursor.class);
        var completed = processingRelease();
        completed.complete();
        when(cursor.next()).thenReturn(Optional.of(MANIFEST_KEY), Optional.empty());
        when(releases.findById(UUID.fromString(RELEASE_ID))).thenReturn(Optional.of(completed));

        assertEquals("RECONCILED", service.reconcile(store, cursor, BUCKET, "eu-west-2", context));

        verify(store, never()).isClean(any(), any(), any());
        verify(registration, never()).register(any(), any(), any());
    }

    @Test
    void skipsManifestUntilItsScanIsClean() throws Exception {
        var store = mock(ReleaseStore.class);
        var cursor = mock(ReconciliationCursor.class);
        when(cursor.next()).thenReturn(Optional.of(MANIFEST_KEY), Optional.empty());
        when(releases.findById(UUID.fromString(RELEASE_ID))).thenReturn(Optional.empty());
        when(store.isClean(BUCKET, MANIFEST_KEY, null)).thenReturn(false);

        assertEquals("RECONCILED", service.reconcile(store, cursor, BUCKET, "eu-west-2", context));

        verify(store, never()).read(any(), any());
        verify(registration, never()).register(any(), any(), any());
    }

    @Test
    void reportsMalformedManifestKeyForRetry() throws Exception {
        var store = mock(ReleaseStore.class);
        var cursor = mock(ReconciliationCursor.class);
        when(cursor.next())
                .thenReturn(Optional.of("manifests/2026-10-06/not-a-uuid.json"), Optional.empty());

        var failure =
                assertThrows(
                        IllegalStateException.class,
                        () -> service.reconcile(store, cursor, BUCKET, "eu-west-2", context));

        assertEquals(1, failure.getSuppressed().length);
        assertTrue(failure.getSuppressed()[0].getMessage().contains("not-a-uuid.json"));
    }

    @Test
    void importCsvStartsReconciliationFromConfiguredProperties() throws Exception {
        when(properties.scannedBucket()).thenReturn(BUCKET);
        when(properties.region()).thenReturn("eu-west-2");
        when(s3.listObjectsV2(any(ListObjectsV2Request.class)))
                .thenReturn(ListObjectsV2Response.builder().build());

        assertEquals("RECONCILED", service.importCsv(Map.of(), context));
    }

    @Test
    void recordsPromotionFailuresAndAllowsRetry() throws Exception {
        var cursor = mock(ReconciliationCursor.class);
        when(cursor.next()).thenReturn(Optional.empty());
        var release =
                new Release(
                        UUID.fromString(RELEASE_ID),
                        uk.gov.companieshouse.release.model.SupplyType.FULL,
                        null,
                        null,
                        MANIFEST_KEY,
                        "digest",
                        "{}");
        when(releases.findTop20ByStatusOrderByValidToAsc(Release.Status.PROCESSING))
                .thenReturn(List.of(release));
        doThrow(new IllegalStateException("promotion failed"))
                .when(promotion)
                .promote(release.getId());

        var store = mock(ReleaseStore.class);
        var failure =
                assertThrows(
                        IllegalStateException.class,
                        () -> service.reconcile(store, cursor, BUCKET, "eu-west-2", context));

        assertEquals(
                "Some manifests, files or promotions failed; successful work remains committed for retry",
                failure.getMessage());
        assertEquals(2, failure.getSuppressed().length);
        assertTrue(failure.getSuppressed()[0].getMessage().contains(RELEASE_ID));
        assertTrue(failure.getSuppressed()[1].getMessage().contains(RELEASE_ID));
    }

    @Test
    void stopsReconcilingWhenLambdaIsNearTimeout() throws Exception {
        when(context.getRemainingTimeInMillis()).thenReturn(29_999);
        when(releases.findTop20ByStatusOrderByValidToAsc(Release.Status.PROCESSING))
                .thenReturn(List.of(processingRelease()));
        var cursor = mock(ReconciliationCursor.class);

        assertEquals(
                "RECONCILED",
                service.reconcile(mock(ReleaseStore.class), cursor, BUCKET, "eu-west-2", context));

        verify(cursor, never()).next();
        verify(promotion, never()).promote(any());
    }

    @Test
    void importsOnlyCleanReadyFiles() throws Exception {
        var store = mock(ReleaseStore.class);
        var manifest = mock(ReleaseManifest.class);
        UUID id = UUID.fromString(RELEASE_ID);
        when(manifest.id()).thenReturn(id);
        var reference =
                JSON.createObjectNode()
                        .put("key", OBJECT_KEY)
                        .put("version", "version-1")
                        .put("sha256", SHA256);
        when(store.read(BUCKET, "ready/" + RELEASE_ID + "/add_gb_royalmailaddress.json"))
                .thenReturn(reference);
        when(store.clean(BUCKET, reference)).thenReturn(true);
        when(importer.importClean(
                        id,
                        Dataset.ADD_GB_ROYALMAILADDRESS,
                        BUCKET,
                        OBJECT_KEY,
                        "version-1",
                        SHA256,
                        "eu-west-2"))
                .thenReturn(true);

        assertTrue(
                ImportService.importReady(
                        store,
                        BUCKET,
                        manifest,
                        Dataset.ADD_GB_ROYALMAILADDRESS,
                        importer,
                        "eu-west-2"));

        verify(store).clean(BUCKET, reference);
        verify(importer)
                .importClean(
                        id,
                        Dataset.ADD_GB_ROYALMAILADDRESS,
                        BUCKET,
                        OBJECT_KEY,
                        "version-1",
                        SHA256,
                        "eu-west-2");
    }

    @Test
    void doesNotImportMissingOrUncleanReadyFiles() throws Exception {
        var store = mock(ReleaseStore.class);
        var manifest = mock(ReleaseManifest.class);
        when(manifest.id()).thenReturn(UUID.fromString(RELEASE_ID));
        var reference =
                JSON.createObjectNode()
                        .put("key", OBJECT_KEY)
                        .put("version", "version-1")
                        .put("sha256", SHA256);
        when(store.read(BUCKET, "ready/" + RELEASE_ID + "/add_gb_royalmailaddress.json"))
                .thenReturn(null, reference);
        when(store.clean(BUCKET, reference)).thenReturn(false);

        assertFalse(
                ImportService.importReady(
                        store,
                        BUCKET,
                        manifest,
                        Dataset.ADD_GB_ROYALMAILADDRESS,
                        importer,
                        "eu-west-2"));
        assertFalse(
                ImportService.importReady(
                        store,
                        BUCKET,
                        manifest,
                        Dataset.ADD_GB_ROYALMAILADDRESS,
                        importer,
                        "eu-west-2"));

        verify(importer, never()).importClean(any(), any(), any(), any(), any(), any(), any());
    }

    @Test
    void rejectsReadyFileFromAnotherReleaseOrDataset() {
        var manifest = mock(ReleaseManifest.class);
        when(manifest.id()).thenReturn(UUID.fromString(RELEASE_ID));
        var foreignReference =
                new ObjectReference(
                        "quarantine/csv/aaaaaaaa-aaaa-aaaa-aaaa-aaaaaaaaaaaa/add_gb_royalmailaddress/"
                                + "12345678-1234-1234-1234-123456789abc.csv",
                        "version-1",
                        SHA256);

        var failure =
                assertThrows(
                        IllegalArgumentException.class,
                        () ->
                                ImportService.validateReference(
                                        manifest,
                                        Dataset.ADD_GB_ROYALMAILADDRESS,
                                        foreignReference));

        assertTrue(failure.getMessage().contains("CSV must belong to release and dataset"));
    }

    @Test
    void stopsImportingWhenLambdaIsNearTimeout() {
        when(context.getRemainingTimeInMillis()).thenReturn(59_999);

        var failures =
                ImportService.importAvailable(
                        mock(ReleaseStore.class),
                        BUCKET,
                        mock(ReleaseManifest.class),
                        importer,
                        "eu-west-2",
                        context);

        assertTrue(failures.isEmpty());
    }

    @Test
    void collectsReadyFileReadFailuresForRetry() throws Exception {
        var store = mock(ReleaseStore.class);
        when(store.read(eq(BUCKET), org.mockito.ArgumentMatchers.startsWith("ready/")))
                .thenThrow(new IllegalStateException("S3 unavailable"));

        var failures =
                ImportService.importAvailable(
                        store, BUCKET, mock(ReleaseManifest.class), importer, "eu-west-2", context);

        assertEquals(Dataset.values().length, failures.size());
        assertTrue(
                failures.stream()
                        .allMatch(failure -> failure.getMessage().equals("S3 unavailable")));
    }

    private Release processingRelease() {
        return new Release(
                UUID.fromString(RELEASE_ID),
                uk.gov.companieshouse.release.model.SupplyType.FULL,
                null,
                null,
                MANIFEST_KEY,
                "digest",
                "{}");
    }
}
