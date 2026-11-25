package uk.gov.companieshouse.addresslookup.lambda.unzip.service;

import static uk.gov.companieshouse.addresslookup.lambda.shared.runtime.RuntimeSupport.check;
import static uk.gov.companieshouse.addresslookup.lambda.shared.runtime.RuntimeSupport.required;

import com.amazonaws.services.lambda.runtime.Context;
import com.fasterxml.jackson.databind.JsonNode;

import org.springframework.stereotype.Component;

import software.amazon.awssdk.services.s3.S3Client;

import uk.gov.companieshouse.addresslookup.lambda.shared.acquisition.AcquisitionProperties;
import uk.gov.companieshouse.addresslookup.lambda.shared.acquisition.Commands;
import uk.gov.companieshouse.addresslookup.lambda.shared.storage.ReconciliationCursor;
import uk.gov.companieshouse.addresslookup.lambda.shared.storage.ReleaseStore;
import uk.gov.companieshouse.addresslookup.lambda.shared.util.KeyLayout;
import uk.gov.companieshouse.logging.Logger;
import uk.gov.companieshouse.logging.LoggerFactory;
import uk.gov.companieshouse.release.model.Dataset;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.Map;

@Component
public final class ExtractionReconciler {
    private static final Logger LOG = LoggerFactory.getLogger(ExtractionReconciler.class.getName());
    private final S3Client s3;
    private final AcquisitionProperties properties;
    private final Commands commands;
    private final ExtractionService extraction;

    public ExtractionReconciler(
            S3Client s3,
            AcquisitionProperties properties,
            Commands commands,
            ExtractionService extraction) {
        this.s3 = s3;
        this.properties = properties;
        this.commands = commands;
        this.extraction = extraction;
    }

    public String reconcile(Context context) throws Exception {
        String bucket = AcquisitionProperties.required(properties.landingBucket(), "LANDING_BUCKET");
        String control = AcquisitionProperties.required(properties.controlBucket(), "CONTROL_BUCKET");
        var store = new ReleaseStore(s3);
        var failures = new ArrayList<Exception>();
        for (var feed : KeyLayout.Feed.values()) {
            var cursor =
                    new ReconciliationCursor(
                            s3,
                            control,
                            bucket,
                            KeyLayout.feedManifestPrefix(feed),
                            "extraction-" + feed.name(),
                            KeyLayout::isDataPackageManifestKey);
            var visited = new HashSet<String>();
            for (int count = 0;
                    count < properties.sweepLimit() && context.getRemainingTimeInMillis() > 60000;
                    count++) {
                var next = cursor.next();
                if (next.isEmpty() || !visited.add(next.get())) break;
                try {
                    reconcileManifest(store, bucket, next.get(), context);
                } catch (Exception failure) {
                    failures.add(
                            new IllegalStateException("Cannot reconcile manifest " + next.get(), failure));
                }
            }
        }
        if (!failures.isEmpty()) {
            var failure =
                    new IllegalStateException("Some extraction work failed; retry on the next sweep");
            for (Exception cause : failures) {
                LOG.error("Extraction reconciliation failed", cause, Map.of("failure_type", cause.getClass().getSimpleName()));
            }
            failures.forEach(failure::addSuppressed);
            throw failure;
        }
        return "RECONCILED";
    }

    private void reconcileManifest(ReleaseStore store, String bucket, String key, Context context)
            throws Exception {
        var packageVersion = KeyLayout.parsePackageVersionManifestKey(key);
        String version = store.version(bucket, key);
        if (!store.isClean(bucket, key, version)) return;
        JsonNode manifest = store.read(bucket, key, version);
        check(manifest != null, "Manifest version no longer exists");
        check(version.equals(store.version(bucket, key)), "Manifest changed after scan");
        check(
                packageVersion.packageId().equals(required(manifest, "dataPackageId"))
                        && packageVersion.versionId().equals(required(manifest, "versionId")),
                "Manifest package/version does not match its Landing key");

        for (var dataset : Dataset.values()) {
            if (context.getRemainingTimeInMillis() <= 60000) return;
            JsonNode entry = manifest.path(dataset.datasetName());
            if (!entry.isObject()) continue;
            String zipKey = KeyLayout.downloadZipKey(packageVersion, dataset);
            JsonNode evidence = store.read(properties.controlBucket(), KeyLayout.checksumKey(zipKey));
            String zipVersion = store.exists(bucket, zipKey) ? store.version(bucket, zipKey) : null;
            if (zipVersion == null
                    || evidence == null
                    || !zipVersion.equals(evidence.path("versionId").asText())
                    || !required(entry.path("zip"), "md5").equalsIgnoreCase(evidence.path("md5").asText())) {
                commands.send("DOWNLOAD", key, dataset.datasetName());
                continue;
            }
            if (!store.isClean(bucket, zipKey, zipVersion)) continue;
            extraction.unzip(
                    Map.of(
                            "detail",
                            Map.of(
                                    "schemaVersion", 1,
                                    "dataset", dataset.datasetName(),
                                    "object", Map.of("bucket", bucket, "key", zipKey, "versionId", zipVersion))),
                    context);
        }
    }
}
