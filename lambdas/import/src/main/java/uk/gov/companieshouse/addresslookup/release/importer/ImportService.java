package uk.gov.companieshouse.addresslookup.release.importer;

import com.amazonaws.services.lambda.runtime.Context;
import org.springframework.stereotype.Component;
import software.amazon.awssdk.services.s3.S3Client;
import uk.gov.companieshouse.addresslookup.lambda.shared.storage.ReconciliationCursor;
import uk.gov.companieshouse.addresslookup.lambda.shared.storage.ReleaseStore;
import uk.gov.companieshouse.addresslookup.lambda.shared.util.KeyLayout;
import uk.gov.companieshouse.addresslookup.release.runtime.ImportProperties;
import uk.gov.companieshouse.addresslookup.releasecore.service.DatasetImportService;
import uk.gov.companieshouse.addresslookup.releasecore.service.PromotionService;
import uk.gov.companieshouse.addresslookup.releasecore.service.ReleaseRegistration;
import uk.gov.companieshouse.addresslookup.releasecore.state.Release;
import uk.gov.companieshouse.addresslookup.releasecore.state.ReleaseRepository;
import uk.gov.companieshouse.logging.Logger;
import uk.gov.companieshouse.logging.LoggerFactory;
import uk.gov.companieshouse.release.model.Dataset;
import uk.gov.companieshouse.release.model.DatasetCatalog;
import uk.gov.companieshouse.release.model.ReleaseManifest;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashSet;
import java.util.List;
import java.util.Map;

import static uk.gov.companieshouse.addresslookup.lambda.shared.runtime.RuntimeSupport.check;
import static uk.gov.companieshouse.addresslookup.lambda.shared.runtime.RuntimeSupport.required;
import static uk.gov.companieshouse.addresslookup.lambda.shared.runtime.RuntimeSupport.sha;

/**
 * Events are wake-ups only; persisted immutable descriptions and clean evidence authorize import.
 */
@Component
public final class ImportService {
  private static final Logger LOG = LoggerFactory.getLogger(ImportService.class.getName());
  private final S3Client s3;
  private final ImportProperties properties;
  private final ReleaseRegistration registration;
  private final DatasetImportService importer;
  private final PromotionService promotion;
  private final ReleaseRepository releases;

  public ImportService(
      S3Client s3,
      ImportProperties properties,
      ReleaseRegistration registration,
      DatasetImportService importer,
      PromotionService promotion,
      ReleaseRepository releases) {
    this.s3 = s3;
    this.properties = properties;
    this.registration = registration;
    this.importer = importer;
    this.promotion = promotion;
    this.releases = releases;
  }

  public String importCsv(Context context) throws Exception {
    String bucket = properties.landingBucket();
    String controlBucket = properties.controlBucket();
    var cursors =
        Arrays.stream(KeyLayout.Feed.values())
            .map(feed ->
                new ReconciliationCursor(
                    s3,
                    controlBucket,
                    bucket,
                    KeyLayout.feedManifestPrefix(feed),
                    "imports-" + feed.name(),
                    KeyLayout::isDataPackageManifestKey))
            .toList();
    return reconcile(new ReleaseStore(s3), cursors, bucket, context);
  }

  String reconcile(
      ReleaseStore store,
      ReconciliationCursor cursor,
      String bucket,
      Context context)
      throws Exception {
    return reconcile(store, List.of(cursor), bucket, context);
  }

  private String reconcile(
      ReleaseStore store,
      List<ReconciliationCursor> cursors,
      String bucket,
      Context context)
      throws Exception {
    var failures = new ArrayList<Exception>();
    // A bad or slow new manifest must not strand releases that were already ready.
    promoteAvailable(failures, context);
    for (var cursor : cursors) {
      var visited = new HashSet<String>();
      for (int count = 0;
          count < properties.sweepLimit() && context.getRemainingTimeInMillis() > 60000;
          count++) {
        var next = cursor.next();
        if (next.isEmpty() || !visited.add(next.get())) break;
        String key = next.get();
        try {
          importManifest(store, bucket, key, context, failures);
        } catch (Exception failure) {
          failures.add(new IllegalStateException("Cannot import manifest " + key, failure));
        }
      }
    }
    promoteAvailable(failures, context);
    if (!failures.isEmpty()) {
      var failure =
          new IllegalStateException(
              "Some manifests, files or promotions failed; successful work remains committed for"
                  + " retry");
      // Lambda's serialized exception does not reliably include suppressed causes.
      for (Exception cause : failures) {
        LOG.error(
            "Import processing failed",
            cause,
            Map.of("failure_type", cause.getClass().getSimpleName()));
      }
      failures.forEach(failure::addSuppressed);
      throw failure;
    }
    return "RECONCILED";
  }

  private void importManifest(
      ReleaseStore store,
      String bucket,
      String key,
      Context context,
      List<Exception> failures)
      throws Exception {
    KeyLayout.PackageVersion packageVersion;
    try {
      packageVersion = KeyLayout.parsePackageVersionManifestKey(key);
    } catch (IllegalArgumentException invalidLandingManifest) {
      return;
    }
    importLandingManifest(store, bucket, key, packageVersion, context, failures);
  }

  private void importLandingManifest(
      ReleaseStore store,
      String bucket,
      String key,
      KeyLayout.PackageVersion packageVersion,
      Context context,
      List<Exception> failures)
      throws Exception {
    var existing = releases.findByManifestKey(key);
    if (existing.isPresent() && existing.get().getStatus() != Release.Status.PROCESSING) return;
    String manifestVersion = store.version(bucket, key);
    if (!store.isClean(bucket, key, manifestVersion)) return;
    var node = store.read(bucket, key, manifestVersion);
    if (node == null) return;
    check(
        packageVersion.packageId().equals(required(node, "dataPackageId"))
            && packageVersion.versionId().equals(required(node, "versionId")),
        "Manifest package/version does not match its Landing key");
    var manifest = ReleaseManifest.read(node);
    existing = releases.findById(manifest.id());
    if (existing.isPresent() && existing.get().getStatus() != Release.Status.PROCESSING) return;
    registration.register(
        manifest, key, sha(node.toString().getBytes(java.nio.charset.StandardCharsets.UTF_8)));
    failures.addAll(
        importAvailable(store, bucket, manifest, packageVersion, context));
  }

  private void promoteAvailable(List<Exception> failures, Context context) {
    // Ordered, bounded candidates: later releases cannot bypass their predecessor anyway.
    for (var release : releases.findTop20ByStatusOrderByValidToAsc(Release.Status.PROCESSING)) {
      if (context.getRemainingTimeInMillis() < 30000) break;
      try {
        promotion.promote(release.getId());
      } catch (Exception failure) {
        failures.add(new IllegalStateException("Cannot promote " + release.getId(), failure));
      }
    }
  }

  private List<Exception> importAvailable(
      ReleaseStore store,
      String bucket,
      ReleaseManifest manifest,
      KeyLayout.PackageVersion packageVersion,
      Context context) {
    var failures = new ArrayList<Exception>();
    for (var dataset : DatasetCatalog.datasets()) {
      if (context.getRemainingTimeInMillis() < 60000) break;
      try {
        importDataset(store, bucket, manifest, packageVersion, dataset);
      } catch (Exception failure) {
        failures.add(failure);
      }
    }
    return failures;
  }

  private void importDataset(
      ReleaseStore store,
      String bucket,
      ReleaseManifest manifest,
      KeyLayout.PackageVersion packageVersion,
      Dataset dataset)
      throws Exception {
    String key =
        KeyLayout.extractedCsvKey(packageVersion, dataset, dataset.datasetName() + ".csv");
    if (!store.exists(bucket, key)) return;
    String version = store.version(bucket, key);
    if (!store.isClean(bucket, key, version)) return;
    var evidence = store.read(bucket, KeyLayout.checksumKey(key));
    if (evidence == null) return;
    check(version.equals(required(evidence, "versionId")), "CSV checksum version mismatch");
    String checksum = required(evidence, "sha256");
    check(checksum.matches("[a-f0-9]{64}"), "Invalid CSV checksum evidence");
    try (var csv = store.open(bucket, key, version)) {
      importer.importClean(manifest.id(), dataset, csv, bucket, key, version, checksum);
    }
  }
}
