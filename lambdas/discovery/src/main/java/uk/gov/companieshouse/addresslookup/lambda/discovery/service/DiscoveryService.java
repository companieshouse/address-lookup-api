package uk.gov.companieshouse.addresslookup.lambda.discovery.service;

import static uk.gov.companieshouse.addresslookup.lambda.shared.runtime.RuntimeSupport.JSON;
import static uk.gov.companieshouse.addresslookup.lambda.shared.runtime.RuntimeSupport.check;
import static uk.gov.companieshouse.addresslookup.lambda.shared.runtime.RuntimeSupport.detail;
import static uk.gov.companieshouse.addresslookup.lambda.shared.runtime.RuntimeSupport.required;
import static uk.gov.companieshouse.addresslookup.lambda.shared.runtime.RuntimeSupport.sanitizeUrl;
import static uk.gov.companieshouse.release.model.SupplyType.COU;
import static uk.gov.companieshouse.release.model.SupplyType.FULL;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.core.TreeNode;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.fasterxml.jackson.databind.node.TextNode;

import org.apache.commons.lang3.StringUtils;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

import software.amazon.awssdk.annotations.NotNull;
import software.amazon.awssdk.core.ResponseInputStream;
import software.amazon.awssdk.core.sync.RequestBody;
import software.amazon.awssdk.services.s3.S3Client;
import software.amazon.awssdk.services.s3.model.GetObjectRequest;
import software.amazon.awssdk.services.s3.model.GetObjectResponse;
import software.amazon.awssdk.services.s3.model.PutObjectRequest;
import software.amazon.awssdk.services.s3.model.S3Exception;

import uk.gov.companieshouse.addresslookup.lambda.discovery.exception.DiscoveryLambdaException;
import uk.gov.companieshouse.addresslookup.lambda.discovery.model.OrderSummary;
import uk.gov.companieshouse.addresslookup.lambda.discovery.model.VersionCursor;
import uk.gov.companieshouse.addresslookup.lambda.shared.acquisition.AcquisitionProperties;
import uk.gov.companieshouse.addresslookup.lambda.shared.acquisition.Commands;
import uk.gov.companieshouse.addresslookup.lambda.shared.os.OsClient;
import uk.gov.companieshouse.logging.Logger;
import uk.gov.companieshouse.logging.LoggerFactory;
import uk.gov.companieshouse.release.model.DatasetDefault;
import uk.gov.companieshouse.release.model.DetailType;
import uk.gov.companieshouse.release.model.Download;
import uk.gov.companieshouse.release.model.PackageVersionData;
import uk.gov.companieshouse.release.model.ReleaseManifest;
import uk.gov.companieshouse.release.model.SupplyType;

import java.nio.charset.StandardCharsets;
import java.time.LocalDate;
import java.time.format.DateTimeFormatter;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

/**
 * Scheduled acquisition; the supply cursor is published acquisition descriptions, not the DB
 * watermark.
 */
@Component
public final class DiscoveryService {

    private static final Logger LOG = LoggerFactory.getLogger(DiscoveryService.class.getName());

    private final S3Client s3Client;
    private final OsClient osClient;
    private final Commands commands;
    private final AcquisitionProperties properties;

    private final List<DatasetDefault> datasets;

    private record DownloadDoublet(Download orderSummaryDownload, Download zipDownload) {}

    private static final String DATA_PACKAGE_FIELD = "data_package";
    private static final String VERSION_ID_FIELD = "version_id";
    private static final String RELEASE_ID_FIELD = "release_id";
    private static final String CURSOR_KEY = "control/address-release/active.json";
    private static final String PATH_KEY_STUB = "acquisitions/%s/%s.json";

    private static final String MISSING_CURSOR_FILE_MESSAGE =
            "The active.json Cursor file could not be found in the S3 bucket.";
    private static final String NO_COMMON_CONTIGUOUS_RELEASE_RESPONSE =
            "NO_COMMON_CONTIGUOUS_RELEASE";

    private static final Set<String> SUPPLY_TYPE_SET = Set.of(FULL.name(), SupplyType.COU.name());

    @Autowired
    public DiscoveryService(
            S3Client s3Client,
            OsClient osClient,
            Commands commands,
            AcquisitionProperties properties) {
        this.s3Client = s3Client;
        this.osClient = osClient;
        this.commands = commands;
        this.properties = properties;
        datasets = properties.datasets();
        if (datasets.isEmpty()) {
            throw new DiscoveryLambdaException("No datasets defined");
        }
    }

    public String discover(Map<String, Object> event) throws Exception {
        return discover(detail(event));
    }

    public String discover(String event) throws Exception {
        return discover(detail(event));
    }

    private String discover(JsonNode input) throws Exception {

        SupplyType mode = deriveMode(input);

        LOG.debug("Starting discovery", Map.of("mode", mode.name()));

        String urlPackageVersionData = "";
        VersionCursor cursor = null;
        String packageId = null;
        if (mode == FULL) {
            // Get version from input
            JsonNode versionsNode = input.get("versions");
            check(versionsNode != null, "versions node missing from request for FULL mode");
            String versionId = required(versionsNode, VERSION_ID_FIELD);
            packageId = required(versionsNode, DATA_PACKAGE_FIELD);

            urlPackageVersionData =
                    properties
                            .osHttpURLTemplate()
                            .replace("{version}", versionId)
                            .replace("{dataPackage}", packageId);
        } else if (mode == COU) {
            // Get the previous version from the Cursor in the S3 bucket
            cursor = getCurrentCursor();
            packageId = cursor.dataPackageId();

            urlPackageVersionData = getNextVersionUrl(cursor);
            if (urlPackageVersionData == null) {
                LOG.error("Failed to get next version URL", Map.of("mode", mode.name()));
                return "No Next Version URL";
            }
        }

        PackageVersionData packageVersionData = getPackageVersionData(urlPackageVersionData);

        check(
                packageVersionData != null,
                String.format("No Package Version Data for [%s]", urlPackageVersionData));

        LOG.debug(
                "Retrieved package version data",
                Map.of(VERSION_ID_FIELD, packageVersionData.id()));

        Map<DatasetDefault, DownloadDoublet> datasetDownloadsMap =
                verifyPackageVersionDataAndReturnDownloads(packageVersionData);

        Map<String, OrderSummary> orderSummaries =
                retrieveAndValidateOrderSummaries(datasetDownloadsMap);

        LocalDate targetDate = calculateTargetDateIfThereIsAValidOne(orderSummaries);

        if (targetDate == null) {
            return NO_COMMON_CONTIGUOUS_RELEASE_RESPONSE;
        }
        LOG.debug("Calculated target date", Map.of("target_date", targetDate.toString()));

        ReleaseManifest releaseManifest =
                generateReleaseManifest(
                        datasetDownloadsMap,
                        packageVersionData,
                        packageId,
                        orderSummaries,
                        targetDate,
                        cursor != null ? cursor.lastProcessedDate() : null);

        LOG.debug(
                "Generated release manifest",
                Map.of(RELEASE_ID_FIELD, releaseManifest.id().toString()));

        final String planKey = saveManifestReturnPlanKey(releaseManifest);

        datasets.stream()
                .map(DatasetDefault::name)
                .forEach(dataset -> publishDownloadCommands(planKey, dataset));

        cursor =
                new VersionCursor(
                        packageId,
                        packageVersionData.id(),
                        targetDate.format(DateTimeFormatter.ISO_LOCAL_DATE));

        saveOrUpdateCursorToS3Bucket(cursor);

        String releaseId = releaseManifest.id().toString();
        LOG.info("Discovery completed successfully", Map.of(RELEASE_ID_FIELD, releaseId));

        return releaseId;
    }

    private SupplyType deriveMode(JsonNode input) {
        String mode = required(input, "mode");
        check(SUPPLY_TYPE_SET.contains(mode), "Unknown mode");
        return SupplyType.valueOf(mode);
    }

    private VersionCursor getCurrentCursor() throws Exception {

        ResponseInputStream<GetObjectResponse> response =
                s3Client.getObject(
                        GetObjectRequest.builder()
                                .bucket(properties.sourceBucket())
                                .key(CURSOR_KEY)
                                .build());

        check(response != null, MISSING_CURSOR_FILE_MESSAGE);
        return JSON.readValue(response, VersionCursor.class);
    }

    private void saveOrUpdateCursorToS3Bucket(Object payload) throws JsonProcessingException {
        saveToS3Bucket(payload, CURSOR_KEY, false);
    }

    private void saveNewOnlyToS3Bucket(Object payload, String key) throws JsonProcessingException {
        saveToS3Bucket(payload, key, true);
    }

    private void saveToS3Bucket(Object payload, String key, boolean ifNoneMatch)
            throws JsonProcessingException {
        try {
            s3Client.putObject(
                    PutObjectRequest.builder()
                            .bucket(properties.sourceBucket())
                            .key(key)
                            .ifNoneMatch(ifNoneMatch ? "*" : null)
                            .contentType("application/json")
                            .build(),
                    RequestBody.fromString(JSON.writeValueAsString(payload)));
        } catch (S3Exception e) {
            LOG.error(
                    "Failed to save to S3",
                    e,
                    Map.of("bucket", properties.sourceBucket(), "key", key));
            if (e.statusCode() != 412) throw e;
        }
    }

    private String getNextVersionUrl(@NotNull VersionCursor cursor) {
        String urlForPrevious =
                generatePackageVersionDataOsUrl(
                        cursor.lastProcessedVersionId(), cursor.dataPackageId());

        PackageVersionData packageVersionData =
                retrieveOSData(urlForPrevious, PackageVersionData.class);
        if (packageVersionData == null) {
            return null;
        }
        return packageVersionData.nextVersionUrl();
    }

    private PackageVersionData getPackageVersionData(String url) {
        LOG.info("Retrieving package version data", Map.of("url", sanitizeUrl(url)));
        return retrieveOSData(url, PackageVersionData.class);
    }

    private Map<DatasetDefault, DownloadDoublet> verifyPackageVersionDataAndReturnDownloads(
            PackageVersionData packageVersionData) {

        Map<DatasetDefault, DownloadDoublet> datasetDownloadsMap = new HashMap<>();

        datasets.forEach(datasetDefault -> {
            Download zipDownload =
                    findMatchingDownload(packageVersionData, datasetDefault.name() + ".zip");
            Download orderSummaryDownload =
                    findMatchingDownload(
                            packageVersionData, datasetDefault.name() + "_orderSummary.json");
            check(
                    zipDownload.md5() != null,
                    String.format("md5 value missing for %s ZIP download", datasetDefault.name()));
            datasetDownloadsMap.put(
                    datasetDefault, new DownloadDoublet(orderSummaryDownload, zipDownload));
        });

        check(!datasetDownloadsMap.isEmpty(), "No Data to update");
        return datasetDownloadsMap;
    }

    private Download findMatchingDownload(PackageVersionData packageVersionData, String filename) {
        return packageVersionData.downloads().stream()
                .filter(download -> matchDownloadToDataset(download, filename))
                .findFirst()
                .orElseThrow(() ->
                        new DiscoveryLambdaException(
                                String.format("Failed to find entry for [%s]", filename)));
    }

    private Map<String, OrderSummary> retrieveAndValidateOrderSummaries(
            Map<DatasetDefault, DownloadDoublet> downloads) {
        final Map<String, OrderSummary> orderSummaries = new HashMap<>();

        downloads
                .entrySet()
                .forEach(entry -> retrieveAndValidateOrderSummary(entry, orderSummaries));

        return orderSummaries;
    }

    private void retrieveAndValidateOrderSummary(
            Map.Entry<DatasetDefault, DownloadDoublet> entry,
            Map<String, OrderSummary> orderSummaries) {

        String url = entry.getValue().orderSummaryDownload.url();
        OrderSummary orderSummary = retrieveOSData(url, OrderSummary.class);
        if (orderSummary == null) {
            LOG.error("Failed to retrieve order summary", Map.of("url", sanitizeUrl(url)));
            return;
        }
        verifyOrderSummary(orderSummary, entry.getKey());
        orderSummaries.put(entry.getKey().name(), orderSummary);
    }

    private boolean matchDownloadToDataset(Download download, String dataset) {
        if (download == null || StringUtils.isEmpty(download.fileName())) return false;

        return (download.fileName().startsWith(dataset));
    }

    private void verifyOrderSummary(OrderSummary orderSummary, DatasetDefault datasetDefault) {
        check(
                !StringUtils.isEmpty(orderSummary.featureName()),
                String.format(
                        "featureName missing from Order Summary for [%s]", datasetDefault.name()));

        check(
                orderSummary.featureName().replace("-", "_").equals(datasetDefault.name()),
                String.format(
                        "featureName [%s] does not match expected value [%s] for the Order Summary",
                        orderSummary.featureName(), datasetDefault.name()));

        check(
                datasetDefault.version().equals(orderSummary.schemaVersion()),
                String.format(
                        "Order summary version wrong, expecting [%s], actual [%s], for dataset [%s]",
                        datasetDefault.version(),
                        orderSummary.schemaVersion(),
                        datasetDefault.name()));

        check(
                orderSummary.recordCount() > -1,
                String.format(
                        "Order summary record count invalid [%s], for dataset [%s]",
                        orderSummary.recordCount(), datasetDefault.name()));

        check(
                orderSummary.validFromDate() == null
                        || orderSummary.validFromDate().isBefore(orderSummary.validToDate()),
                String.format(
                        "To Date [%s] is not after From Date [%s] for dataset [%s]",
                        orderSummary.validToDate(),
                        orderSummary.validFromDate(),
                        datasetDefault.name()));
    }

    private String generatePackageVersionDataOsUrl(String versionId, String packageId) {
        return properties
                .osHttpURLTemplate()
                .replace("{version}", versionId)
                .replace("{dataPackage}", packageId);
    }

    private LocalDate calculateTargetDateIfThereIsAValidOne(
            Map<String, OrderSummary> orderSummaryMap) {
        final LocalDate targetDate = orderSummaryMap.get(datasets.getFirst().name()).validToDate();

        if (orderSummaryMap.values().stream()
                .map(OrderSummary::validToDate)
                .anyMatch(toDate -> toDate == null || !toDate.equals(targetDate))) {
            return null;
        }
        return targetDate;
    }

    private ReleaseManifest generateReleaseManifest(
            Map<DatasetDefault, DownloadDoublet> datasets,
            PackageVersionData packageVersionData,
            String dataPackageId,
            Map<String, OrderSummary> orderSummaries,
            LocalDate targetDate,
            String previousDate)
            throws Exception {

        final ObjectNode rootNode = JSON.createObjectNode();
        String mode = packageVersionData.supplyType().name();
        rootNode.set("mode", new TextNode(mode));
        rootNode.set("target", new TextNode(targetDate.format(DateTimeFormatter.ISO_DATE)));
        if (previousDate != null) {
            rootNode.set("previous", new TextNode(previousDate));
        }
        rootNode.set("dataPackageId", new TextNode(dataPackageId));
        rootNode.set("versionId", new TextNode(packageVersionData.id()));

        datasets.forEach((key, value) -> {
            ObjectNode datasetNode = JSON.createObjectNode();

            Download zipDownload = value.zipDownload();

            ObjectNode zipNode = JSON.createObjectNode();
            zipNode.put("fileName", zipDownload.fileName())
                    .put("url", zipDownload.url())
                    .put("md5", zipDownload.md5());

            OrderSummary orderSummary = orderSummaries.get(key.name());
            JsonNode summaryNode = JSON.valueToTree(orderSummary);

            datasetNode.set("zip", zipNode);
            datasetNode.set("summary", summaryNode);
            rootNode.set(key.name(), datasetNode);
        });

        UUID releaseId = generateReleaseId(rootNode);

        ObjectNode manifestNode =
                JSON.createObjectNode().put("releaseId", releaseId.toString()).setAll(rootNode);
        return ReleaseManifest.read(manifestNode);
    }

    private UUID generateReleaseId(JsonNode releaseManifest) throws Exception {

        JsonNode copyOfReleaseManifest = releaseManifest.deepCopy();

        copyOfReleaseManifest.findValues("zip").forEach(zip -> ((ObjectNode) zip).remove("url"));

        String json = JSON.writeValueAsString(copyOfReleaseManifest);
        return UUID.nameUUIDFromBytes(json.getBytes(StandardCharsets.UTF_8));
    }

    private String saveManifestReturnPlanKey(ReleaseManifest releaseManifest)
            throws JsonProcessingException {
        String pathKey =
                String.format(PATH_KEY_STUB, releaseManifest.target(), releaseManifest.id());

        saveNewOnlyToS3Bucket(releaseManifest.json(), pathKey);
        return pathKey;
    }

    private void publishDownloadCommands(String planKey, String dataset)
            throws DiscoveryLambdaException {
        try {
            commands.send(DetailType.DOWNLOAD.name(), planKey, dataset);
            LOG.info("Published download command", Map.of("dataset", dataset, "plan_key", planKey));
        } catch (Throwable throwable) {
            throw new DiscoveryLambdaException(
                    "An exception occurred whilst attempting to send a DOWNLOAD command to the EventBridge",
                    throwable);
        }
    }

    private <T> T retrieveOSData(String url, Class<T> clazz) {
        TreeNode treeNode;
        try {
            treeNode = osClient.json(url);
        } catch (Exception e) {
            LOG.error("Failed to get data from OS", e, Map.of("url", sanitizeUrl(url)));
            throw new DiscoveryLambdaException(e);
        }
        if (treeNode == null || treeNode.size() == 0) {
            LOG.error("OS returned no data", Map.of("url", sanitizeUrl(url)));
            return null;
        }
        T t;

        try {
            t = JSON.treeToValue(treeNode, clazz);
        } catch (JsonProcessingException e) {
            LOG.error(
                    "Failed to parse OS response",
                    e,
                    Map.of("response_type", clazz.getName(), "url", sanitizeUrl(url)));
            throw new DiscoveryLambdaException(e);
        }

        return t;
    }
}
