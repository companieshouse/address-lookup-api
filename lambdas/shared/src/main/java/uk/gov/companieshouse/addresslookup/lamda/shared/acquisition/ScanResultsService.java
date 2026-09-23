package uk.gov.companieshouse.addresslookup.lamda.shared.acquisition;

import com.amazonaws.services.lambda.runtime.Context;
import software.amazon.awssdk.services.s3.S3Client;
import uk.gov.companieshouse.addresslookup.lamda.shared.storage.ReconciliationCursor;
import uk.gov.companieshouse.addresslookup.lamda.shared.storage.ReleaseStore;
import uk.gov.companieshouse.release.model.Dataset;
import uk.gov.companieshouse.release.model.ReleaseManifest;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Map;

import static uk.gov.companieshouse.addresslookup.lamda.shared.runtime.RuntimeSupport.*;

/** Scan events and bounded scheduled sweeps reconcile each file independently. */
public final class ScanResultsService {
  private final S3Client s3;
  private final Commands commands;
  private final AcquisitionProperties properties;

  public ScanResultsService(S3Client s3, Commands commands, AcquisitionProperties properties) {
    this.s3 = s3;
    this.commands = commands;
    this.properties = properties;
    AcquisitionProperties.required(properties.scannedBucket(), "SCANNED_BUCKET");
  }

  public String scan(Map<String, Object> event, Context context) throws Exception {
    check(
        ("os.release".equals(event.get("source"))
                && "RECONCILE_SCANS".equals(event.get("detail-type")))
            || ("aws.guardduty".equals(event.get("source"))
                && "GuardDuty Malware Protection Object Scan Result"
                    .equals(event.get("detail-type"))),
        "Unexpected scan event");
    var failures = new ArrayList<Exception>();
    // Report terminal events even if the object's status tag has not arrived yet.
    if ("aws.guardduty".equals(event.get("source"))) {
      var detail = detail(event);
      String status = required(detail.path("scanResultDetails"), "scanResultStatus");
      if (!ReleaseStore.CLEAN.equals(status))
        failures.add(
            new IllegalStateException(
                "GuardDuty scan " + status + ": " + detail.path("s3ObjectDetails")));
    }
    var store = new ReleaseStore(s3);
    String source = properties.sourceBucket(), checked = properties.scannedBucket();
    var cursor = new ReconciliationCursor(s3, source, "acquisitions/", "scans");
    var visited = new HashSet<String>();
    for (int count = 0;
        count < properties.sweepLimit() && context.getRemainingTimeInMillis() > 60000;
        count++) {
      var next = cursor.next();
      if (next.isEmpty() || !visited.add(next.get())) break;
      try {
        failures.addAll(reconcilePlan(store, source, checked, next.get(), commands));
      } catch (Exception failure) {
        failures.add(new IllegalStateException("Cannot reconcile " + next.get(), failure));
      }
    }
    if (!failures.isEmpty()) {
      var failure =
          new IllegalStateException(
              "Scan reconciliation requires attention; inspect the error log");
      // Lambda's serialized exception does not reliably include suppressed causes.
      failures.forEach(Throwable::printStackTrace);
      failures.forEach(failure::addSuppressed);
      throw failure; // Existing Lambda Errors alarm and OnFailure DLQ report this invocation.
    }
    return "RECONCILED";
  }

  static List<Exception> reconcilePlan(
      ReleaseStore store, String source, String checked, String key, Commands commands)
      throws Exception {
    var plan = store.read(source, key);
    var manifest = ReleaseManifest.read(plan);
    String id = manifest.id().toString();
    store.immutable(checked, "manifests/" + manifest.target() + "/" + id + ".json", plan);
    var failures = new ArrayList<Exception>();
    for (var dataset : Dataset.values()) {
      try {
        reconcileFile(store, source, checked, key, id, dataset.datasetName(), commands);
      } catch (Exception failure) {
        failures.add(
            new IllegalStateException(
                "Cannot prepare " + id + "/" + dataset.datasetName(), failure));
      }
    }
    return failures;
  }

  private static void reconcileFile(
      ReleaseStore store,
      String source,
      String checked,
      String planKey,
      String id,
      String dataset,
      Commands commands)
      throws Exception {
    var zip = store.receipt(source, id, dataset, "download");
    if (zip == null) {
      commands.send("DOWNLOAD", planKey, dataset);
      return;
    }
    if (!store.clean(source, zip)) return;
    var csv = store.receipt(source, id, dataset, "extract");
    if (csv == null) {
      commands.send("UNZIP", planKey, dataset);
      return;
    }
    if (!store.clean(checked, csv)) return;
    store.immutable(checked, "ready/" + id + "/" + dataset + ".json", csv);
  }
}
