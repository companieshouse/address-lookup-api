package uk.gov.companieshouse.addresslookup.release.importer;

import static uk.gov.companieshouse.addresslookup.lambda.shared.runtime.RuntimeSupport.*;

import com.amazonaws.services.lambda.runtime.Context;
import java.util.*;
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
import uk.gov.companieshouse.release.model.ReleaseManifest;

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
                    KeyLayout.feedManifestPrefix(feed),
                    "imports-" + feed.name()))
            .toList();
    return reconcile(new ReleaseStore(s3), cursors, bucket, properties.region(), context);
  }

  String reconcile(
      ReleaseStore store,
      ReconciliationCursor cursor,
      String bucket,
      String region,
      Context context)
      throws Exception {
    return reconcile(store, List.of(cursor), bucket, region, context);
  }

  private String reconcile(
      ReleaseStore store,
      List<ReconciliationCursor> cursors,
      String bucket,
      String region,
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
          importManifest(store, bucket, key, region, context, failures);
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
      String region,
      Context context,
      List<Exception> failures)
      throws Exception {
    KeyLayout.PackageVersion packageVersion;
    try {
      packageVersion = KeyLayout.parsePackageVersionManifestKey(key);
    } catch (IllegalArgumentException invalidLandingManifest) {
      return;
    }
    importLandingManifest(store, bucket, key, packageVersion, region, context, failures);
  }

  private void importLandingManifest(
      ReleaseStore store,
      String bucket,
      String key,
      KeyLayout.PackageVersion packageVersion,
      String region,
      Context context,
      List<Exception> failures)
      throws Exception {
    if (!store.isClean(bucket, key, null)) return;
    var node = store.read(bucket, key);
    if (node == null) return;
    var manifest = ReleaseManifest.read(node);
    check(key.equals(KeyLayout.dataPackageManifestKey(packageVersion)), "Manifest key mismatch");
    var existing = releases.findById(manifest.id());
    if (existing.isPresent() && existing.get().getStatus() != Release.Status.PROCESSING) return;
    registration.register(
        manifest, key, sha(node.toString().getBytes(java.nio.charset.StandardCharsets.UTF_8)));
    failures.addAll(
        importAvailable(store, bucket, manifest, packageVersion, importer, region, context));
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

  private static List<Exception> importAvailable(
      ReleaseStore store,
      String bucket,
      ReleaseManifest manifest,
      KeyLayout.PackageVersion packageVersion,
      DatasetImportService importer,
      String region,
      Context context) {
    var failures = new ArrayList<Exception>();
    for (var dataset : Dataset.values()) {
      if (context.getRemainingTimeInMillis() < 60000) break;
      try {
        String key =
            KeyLayout.extractedCsvKey(packageVersion, dataset, dataset.datasetName() + ".csv");
        if (!store.exists(bucket, key)) continue;
        String version = store.version(bucket, key);
        if (!store.isClean(bucket, key, version)) continue;
        check(version.equals(store.version(bucket, key)), "CSV changed after scan");
        var evidence = store.read(bucket, KeyLayout.checksumKey(key));
        if (evidence == null) continue;
        check(version.equals(required(evidence, "versionId")), "CSV checksum version mismatch");
        String checksum = required(evidence, "sha256");
        check(checksum.matches("[a-f0-9]{64}"), "Invalid CSV checksum evidence");
        importer.importClean(manifest.id(), dataset, bucket, key, version, checksum, region);
      } catch (Exception failure) {
        failures.add(failure);
      }
    }
    return failures;
  }
}
