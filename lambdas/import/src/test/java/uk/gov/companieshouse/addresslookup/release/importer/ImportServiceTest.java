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
import java.io.ByteArrayInputStream;
import java.nio.charset.StandardCharsets;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;
import org.mockito.Mock;
import org.mockito.MockedStatic;
import org.mockito.junit.jupiter.MockitoExtension;
import software.amazon.awssdk.core.ResponseInputStream;
import software.amazon.awssdk.core.sync.RequestBody;
import software.amazon.awssdk.services.s3.S3Client;
import software.amazon.awssdk.services.s3.model.GetObjectRequest;
import software.amazon.awssdk.services.s3.model.GetObjectResponse;
import software.amazon.awssdk.services.s3.model.GetObjectTaggingRequest;
import software.amazon.awssdk.services.s3.model.GetObjectTaggingResponse;
import software.amazon.awssdk.services.s3.model.HeadObjectRequest;
import software.amazon.awssdk.services.s3.model.HeadObjectResponse;
import software.amazon.awssdk.services.s3.model.ListObjectsV2Request;
import software.amazon.awssdk.services.s3.model.ListObjectsV2Response;
import software.amazon.awssdk.services.s3.model.PutObjectRequest;
import software.amazon.awssdk.services.s3.model.PutObjectResponse;
import software.amazon.awssdk.services.s3.model.S3Object;
import software.amazon.awssdk.services.s3.model.Tag;
import uk.gov.companieshouse.addresslookup.lambda.shared.storage.ReconciliationCursor;
import uk.gov.companieshouse.addresslookup.lambda.shared.storage.ReleaseStore;
import uk.gov.companieshouse.addresslookup.lambda.shared.util.KeyLayout;
import uk.gov.companieshouse.addresslookup.release.runtime.ImportProperties;
import uk.gov.companieshouse.addresslookup.releasecore.service.DatasetImportService;
import uk.gov.companieshouse.addresslookup.releasecore.service.PromotionService;
import uk.gov.companieshouse.addresslookup.releasecore.service.ReleaseRegistration;
import uk.gov.companieshouse.addresslookup.releasecore.state.Release;
import uk.gov.companieshouse.addresslookup.releasecore.state.ReleaseRepository;
import uk.gov.companieshouse.release.model.Dataset;
import uk.gov.companieshouse.release.model.DatasetCatalog;
import uk.gov.companieshouse.release.model.ReleaseManifest;

@ExtendWith(MockitoExtension.class)
class ImportServiceTest {
  private static final String BUCKET = "scanned";
  private static final String RELEASE_ID = "12345678-1234-1234-1234-123456789abc";
  private static final KeyLayout.PackageVersion PACKAGE_VERSION =
      new KeyLayout.PackageVersion(KeyLayout.Feed.DAILY_COU, "20780", "150104");
  private static final String MANIFEST_KEY = KeyLayout.dataPackageManifestKey(PACKAGE_VERSION);
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
    lenient().when(properties.controlBucket()).thenReturn("control");
    lenient()
        .when(releases.findTop20ByStatusOrderByValidToAsc(Release.Status.PROCESSING))
        .thenReturn(List.of());
    lenient().when(context.getRemainingTimeInMillis()).thenReturn(900_000);
  }

  @Test
  void reconcilesCleanManifestAndIgnoresMissingCsvFiles() throws Exception {
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
    try (MockedStatic<ReleaseManifest> mockedManifest = mockStatic(ReleaseManifest.class)) {
      mockedManifest.when(() -> ReleaseManifest.read(manifestNode)).thenReturn(manifest);

      assertEquals("RECONCILED", service.reconcile(store, cursor, BUCKET, "eu-west-2", context));
    }

    verify(registration)
        .register(
            eq(manifest), eq(MANIFEST_KEY), org.mockito.ArgumentMatchers.matches("[a-f0-9]{64}"));
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
  void skipsCompletedManifestAfterReadingItsVersionedManifest() throws Exception {
    var store = mock(ReleaseStore.class);
    var cursor = mock(ReconciliationCursor.class);
    var completed = processingRelease();
    completed.complete();
    var node = JSON.createObjectNode().put("manifest", true);
    var manifest = mock(ReleaseManifest.class);
    when(cursor.next()).thenReturn(Optional.of(MANIFEST_KEY), Optional.empty());
    when(store.isClean(BUCKET, MANIFEST_KEY, null)).thenReturn(true);
    when(store.read(BUCKET, MANIFEST_KEY)).thenReturn(node);
    when(manifest.id()).thenReturn(UUID.fromString(RELEASE_ID));
    when(releases.findById(UUID.fromString(RELEASE_ID))).thenReturn(Optional.of(completed));

    try (MockedStatic<ReleaseManifest> mockedManifest = mockStatic(ReleaseManifest.class)) {
      mockedManifest.when(() -> ReleaseManifest.read(node)).thenReturn(manifest);
      assertEquals("RECONCILED", service.reconcile(store, cursor, BUCKET, "eu-west-2", context));
    }

    verify(store).isClean(BUCKET, MANIFEST_KEY, null);
    verify(registration, never()).register(any(), any(), any());
  }

  @Test
  void revisitsManifestWhoseReleaseIsStillProcessing() throws Exception {
    var store = mock(ReleaseStore.class);
    var cursor = mock(ReconciliationCursor.class);
    when(cursor.next()).thenReturn(Optional.of(MANIFEST_KEY), Optional.empty());
    when(store.isClean(BUCKET, MANIFEST_KEY, null)).thenReturn(false);

    assertEquals("RECONCILED", service.reconcile(store, cursor, BUCKET, "eu-west-2", context));

    verify(store).isClean(BUCKET, MANIFEST_KEY, null);
  }

  @Test
  void skipsManifestUntilItsScanIsClean() throws Exception {
    var store = mock(ReleaseStore.class);
    var cursor = mock(ReconciliationCursor.class);
    when(cursor.next()).thenReturn(Optional.of(MANIFEST_KEY), Optional.empty());
    when(store.isClean(BUCKET, MANIFEST_KEY, null)).thenReturn(false);

    assertEquals("RECONCILED", service.reconcile(store, cursor, BUCKET, "eu-west-2", context));

    verify(store, never()).read(any(), any());
    verify(registration, never()).register(any(), any(), any());
  }

  @Test
  void ignoresKeysThatAreNotFeedManifests() throws Exception {
    var store = mock(ReleaseStore.class);
    var cursor = mock(ReconciliationCursor.class);
    when(cursor.next())
        .thenReturn(
            Optional.of("daily_cou/20780/150104/downloads/not-a-manifest.zip"), Optional.empty());

    assertEquals("RECONCILED", service.reconcile(store, cursor, BUCKET, "eu-west-2", context));

    verify(store, never()).isClean(any(), any(), any());
    verify(registration, never()).register(any(), any(), any());
  }

  @Test
  void ignoresNonManifestObjectsFoundUnderFeedRoots() throws Exception {
    var store = mock(ReleaseStore.class);
    var cursor = mock(ReconciliationCursor.class);
    when(cursor.next())
        .thenReturn(
            Optional.of("daily_cou/20780/150104/downloads/add_gb_royalmailaddress.zip"),
            Optional.empty());

    assertEquals("RECONCILED", service.reconcile(store, cursor, BUCKET, "eu-west-2", context));

    verify(store, never()).isClean(any(), any(), any());
    verify(registration, never()).register(any(), any(), any());
  }

  @Test
  void importCsvStartsReconciliationFromConfiguredProperties() throws Exception {
    when(properties.controlBucket()).thenReturn("control");
    when(properties.landingBucket()).thenReturn(BUCKET);
    when(properties.region()).thenReturn("eu-west-2");
    when(s3.listObjectsV2(any(ListObjectsV2Request.class)))
        .thenReturn(ListObjectsV2Response.builder().build());

    assertEquals("RECONCILED", service.importCsv(context));
  }

  @ParameterizedTest
  @EnumSource(KeyLayout.Feed.class)
  void discoversLandingManifestAndImportsCsvWithCursorInControl(KeyLayout.Feed feed)
      throws Exception {
    String control = "control-bucket";
    String landing = "landing-bucket";
    var packageVersion = new KeyLayout.PackageVersion(feed, "20780", "150104");
    var dataset = Dataset.ADD_GB_ROYALMAILADDRESS;
    String manifestKey = KeyLayout.dataPackageManifestKey(packageVersion);
    String csvKey =
        KeyLayout.extractedCsvKey(packageVersion, dataset, dataset.datasetName() + ".csv");
    String cursorKey = KeyLayout.reconciliationCursorKey("imports-" + feed.name());
    var node =
        JSON.createObjectNode()
            .put("releaseId", RELEASE_ID)
            .put("mode", feed == KeyLayout.Feed.DAILY_COU ? "COU" : "FULL")
            .put("previous", "2026-10-04")
            .put("target", "2026-10-05")
            .put("dataPackageId", packageVersion.packageId())
            .put("versionId", packageVersion.versionId());
    var file = node.putObject(dataset.datasetName());
    file.putObject("summary")
        .put("featureName", dataset.datasetName().replace('_', '-'))
        .put("crs", "http://www.opengis.net/def/crs/EPSG/0/27700")
        .put("schemaVersion", DatasetCatalog.table(dataset).version())
        .put("validFromDate", "2026-10-04")
        .put("validToDate", "2026-10-05")
        .put("recordCount", 1);
    file.putObject("zip").put("md5", "a".repeat(32));
    var manifest = ReleaseManifest.read(node);
    Map<String, Map<String, byte[]>> buckets =
        Map.of(control, new HashMap<>(), landing, new HashMap<>());
    buckets.get(landing).put(manifestKey, JSON.writeValueAsBytes(node));
    buckets.get(landing).put(csvKey, "csv contents".getBytes(StandardCharsets.UTF_8));
    buckets.get(landing).put(
        KeyLayout.checksumKey(csvKey),
        JSON.writeValueAsBytes(
            JSON.createObjectNode().put("versionId", "csv-version").put("sha256", SHA256)));
    when(properties.controlBucket()).thenReturn(control);
    when(properties.landingBucket()).thenReturn(landing);
    when(properties.region()).thenReturn("eu-west-2");
    when(properties.sweepLimit()).thenReturn(10);
    when(s3.listObjectsV2(any(ListObjectsV2Request.class)))
        .thenAnswer(call -> {
          var request = call.getArgument(0, ListObjectsV2Request.class);
          return ListObjectsV2Response.builder()
              .contents(
                  buckets.get(request.bucket()).keySet().stream()
                      .filter(key -> key.startsWith(request.prefix()))
                      .filter(key -> request.startAfter() == null
                          || key.compareTo(request.startAfter()) > 0)
                      .sorted()
                      .limit(request.maxKeys() == null ? Long.MAX_VALUE : request.maxKeys())
                      .map(key -> S3Object.builder().key(key).build())
                      .toList())
              .build();
        });
    when(s3.getObject(any(GetObjectRequest.class)))
        .thenAnswer(call -> {
          var request = call.getArgument(0, GetObjectRequest.class);
          return new ResponseInputStream<>(
              GetObjectResponse.builder().eTag("etag").build(),
              new ByteArrayInputStream(buckets.get(request.bucket()).get(request.key())));
        });
    when(s3.putObject(any(PutObjectRequest.class), any(RequestBody.class)))
        .thenAnswer(call -> {
          var request = call.getArgument(0, PutObjectRequest.class);
          try (var in = call.getArgument(1, RequestBody.class).contentStreamProvider().newStream()) {
            buckets.get(request.bucket()).put(request.key(), in.readAllBytes());
          }
          return PutObjectResponse.builder().build();
        });
    when(s3.getObjectTagging(any(GetObjectTaggingRequest.class)))
        .thenAnswer(call -> {
          var request = call.getArgument(0, GetObjectTaggingRequest.class);
          assertEquals(landing, request.bucket());
          assertTrue(buckets.get(landing).containsKey(request.key()));
          return GetObjectTaggingResponse.builder()
              .tagSet(Tag.builder()
                  .key("GuardDutyMalwareScanStatus")
                  .value(ReleaseStore.CLEAN)
                  .build())
              .build();
        });
    when(s3.headObject(any(HeadObjectRequest.class)))
        .thenAnswer(call -> {
          var request = call.getArgument(0, HeadObjectRequest.class);
          assertEquals(landing, request.bucket());
          assertEquals(csvKey, request.key());
          return HeadObjectResponse.builder().versionId("csv-version").build();
        });

    assertEquals("RECONCILED", service.importCsv(context));

    verify(registration).register(eq(manifest), eq(manifestKey), any());
    verify(importer).importClean(
        manifest.id(), dataset, landing, csvKey, "csv-version", SHA256, "eu-west-2");
    String position =
        JSON.readTree(buckets.get(control).get(cursorKey)).path("after").asText();
    assertTrue(buckets.get(landing).containsKey(position));
    assertTrue(buckets.get(control).keySet().stream().allMatch(cursorKey::equals));
    assertFalse(buckets.get(landing).containsKey(cursorKey));
  }

  @Test
  void importsCsvProducedAtExtractionLayoutFromConfiguredFeedManifest() throws Exception {
    var packageVersion = PACKAGE_VERSION;
    String manifestKey = KeyLayout.dataPackageManifestKey(packageVersion);
    String csvKey =
        KeyLayout.extractedCsvKey(
            packageVersion,
            Dataset.ADD_GB_ROYALMAILADDRESS,
            Dataset.ADD_GB_ROYALMAILADDRESS.datasetName() + ".csv");
    var extractionOutput = KeyLayout.parseLandingObject(csvKey);
    assertEquals(packageVersion, extractionOutput.packageVersion());
    assertEquals(Dataset.ADD_GB_ROYALMAILADDRESS, extractionOutput.dataset());
    assertEquals(KeyLayout.LandingFileType.CSV, extractionOutput.fileType());
    UUID id = UUID.fromString(RELEASE_ID);
    var cursor = mock(ReconciliationCursor.class);
    var store = mock(ReleaseStore.class);
    var node = JSON.createObjectNode().put("manifest", true);
    var evidence = JSON.createObjectNode().put("versionId", "csv-version").put("sha256", SHA256);
    var manifest = mock(ReleaseManifest.class);
    when(cursor.next()).thenReturn(Optional.of(manifestKey), Optional.empty());
    when(store.isClean(BUCKET, manifestKey, null)).thenReturn(true);
    when(store.read(BUCKET, manifestKey)).thenReturn(node);
    when(manifest.id()).thenReturn(id);
    when(releases.findById(id)).thenReturn(Optional.empty());
    when(store.exists(BUCKET, csvKey)).thenReturn(true);
    when(store.version(BUCKET, csvKey)).thenReturn("csv-version");
    when(store.isClean(BUCKET, csvKey, "csv-version")).thenReturn(true);
    when(store.read(BUCKET, KeyLayout.checksumKey(csvKey))).thenReturn(evidence);

    try (MockedStatic<ReleaseManifest> mockedManifest = mockStatic(ReleaseManifest.class)) {
      mockedManifest.when(() -> ReleaseManifest.read(node)).thenReturn(manifest);

      assertEquals("RECONCILED", service.reconcile(store, cursor, BUCKET, "eu-west-2", context));
    }

    verify(registration)
        .register(
            eq(manifest), eq(manifestKey), org.mockito.ArgumentMatchers.matches("[a-f0-9]{64}"));
    verify(importer)
        .importClean(
            id,
            Dataset.ADD_GB_ROYALMAILADDRESS,
            BUCKET,
            csvKey,
            "csv-version",
            SHA256,
            "eu-west-2");
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
    doThrow(new IllegalStateException("promotion failed")).when(promotion).promote(release.getId());

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
