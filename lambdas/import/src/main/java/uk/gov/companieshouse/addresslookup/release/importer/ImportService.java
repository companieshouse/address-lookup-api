package uk.gov.companieshouse.addresslookup.release.importer;

import com.amazonaws.services.lambda.runtime.Context;
import software.amazon.awssdk.services.s3.S3Client;
import uk.gov.companieshouse.addresslookup.lamda.shared.storage.ReconciliationCursor;
import uk.gov.companieshouse.addresslookup.lamda.shared.storage.ReleaseStore;
import uk.gov.companieshouse.addresslookup.release.runtime.ImportProperties;
import uk.gov.companieshouse.addresslookup.releasecore.service.DatasetImportService;
import uk.gov.companieshouse.addresslookup.releasecore.service.PromotionService;
import uk.gov.companieshouse.addresslookup.releasecore.service.ReleaseRegistration;
import uk.gov.companieshouse.addresslookup.releasecore.state.Release;
import uk.gov.companieshouse.addresslookup.releasecore.state.ReleaseRepository;
import uk.gov.companieshouse.release.model.Dataset;
import uk.gov.companieshouse.release.model.ObjectReference;
import uk.gov.companieshouse.release.model.ReleaseManifest;

import java.util.*;

import static uk.gov.companieshouse.addresslookup.lamda.shared.runtime.RuntimeSupport.*;

/**
 * Events are wake-ups only; persisted immutable descriptions and clean evidence authorize import.
 */
public final class ImportService {
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

  public String importCsv(Map<String, Object> event, Context context) throws Exception {
    String bucket = properties.scannedBucket();
    return reconcile(
        new ReleaseStore(s3),
        new ReconciliationCursor(s3, bucket, "manifests/", "imports"),
        bucket,
        properties.region(),
        context);
  }

  String reconcile(
      ReleaseStore store,
      ReconciliationCursor cursor,
      String bucket,
      String region,
      Context context)
      throws Exception {
    var failures = new ArrayList<Exception>();
    // A bad or slow new manifest must not strand releases that were already ready.
    promoteAvailable(failures, context);
    var visited = new HashSet<String>();
    for (int count = 0;
        count < properties.sweepLimit() && context.getRemainingTimeInMillis() > 60000;
        count++) {
      var next = cursor.next();
      if (next.isEmpty() || !visited.add(next.get())) break;
      String key = next.get();
      try {
        // Completed history may use an older schema; don't parse it with today's contract.
        UUID id = manifestId(key);
        var existing = releases.findById(id);
        if (existing.isPresent() && existing.get().getStatus() != Release.Status.PROCESSING)
          continue;
        if (!store.isClean(bucket, key, null)) continue;
        var node = store.read(bucket, key);
        var manifest = ReleaseManifest.read(node);
        check(
            key.equals("manifests/" + manifest.target() + "/" + manifest.id() + ".json"),
            "Manifest key mismatch");
        registration.register(
            manifest, key, sha(node.toString().getBytes(java.nio.charset.StandardCharsets.UTF_8)));
        failures.addAll(importAvailable(store, bucket, manifest, importer, region, context));
      } catch (Exception failure) {
        failures.add(new IllegalStateException("Cannot import manifest " + key, failure));
      }
    }
    promoteAvailable(failures, context);
    if (!failures.isEmpty()) {
      var failure =
          new IllegalStateException(
              "Some manifests, files or promotions failed; successful work remains committed for"
                  + " retry");
      // Lambda's serialized exception does not reliably include suppressed causes.
      failures.forEach(Throwable::printStackTrace);
      failures.forEach(failure::addSuppressed);
      throw failure;
    }
    return "RECONCILED";
  }

  private static UUID manifestId(String key) {
    String name = key.substring(key.lastIndexOf('/') + 1);
    check(name.endsWith(".json"), "Unexpected manifest key");
    return UUID.fromString(name.substring(0, name.length() - 5));
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

  static List<Exception> importAvailable(
      ReleaseStore store,
      String bucket,
      ReleaseManifest manifest,
      DatasetImportService importer,
      String region,
      Context context) {
    var failures = new ArrayList<Exception>();
    for (var dataset : Dataset.values()) {
      if (context.getRemainingTimeInMillis() < 60000) break;
      try {
        importReady(store, bucket, manifest, dataset, importer, region);
      } catch (Exception failure) {
        failures.add(failure);
      }
    }
    return failures;
  }

  static boolean importReady(
      ReleaseStore store,
      String bucket,
      ReleaseManifest manifest,
      Dataset dataset,
      DatasetImportService importer,
      String region)
      throws Exception {
    String evidenceKey = "ready/" + manifest.id() + "/" + dataset.datasetName() + ".json";
    var ref = store.read(bucket, evidenceKey);
    if (ref == null) return false;
    var object = ObjectReference.read(ref);
    validateReference(manifest, dataset, object);
    if (!store.clean(bucket, ref)) return false;
    return importer.importClean(
        manifest.id(), dataset, bucket, object.key(), object.version(), object.sha256(), region);
  }

  static void validateReference(ReleaseManifest manifest, Dataset dataset, ObjectReference ref) {
    check(
        ref.key()
            .matches(
                "quarantine/csv/"
                    + manifest.id()
                    + "/"
                    + dataset.datasetName()
                    + "/[a-f0-9-]{36}\\.csv"),
        "CSV must belong to release and dataset");
  }
}
