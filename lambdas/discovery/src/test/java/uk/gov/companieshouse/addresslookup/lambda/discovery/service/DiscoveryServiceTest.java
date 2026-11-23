package uk.gov.companieshouse.addresslookup.lambda.discovery.service;

import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentMatcher;
import org.mockito.Mock;
import org.mockito.MockedStatic;
import org.mockito.junit.jupiter.MockitoExtension;
import software.amazon.awssdk.core.sync.RequestBody;
import software.amazon.awssdk.services.s3.S3Client;
import software.amazon.awssdk.services.s3.model.GetObjectRequest;
import software.amazon.awssdk.services.s3.model.PutObjectRequest;
import uk.gov.companieshouse.addresslookup.lambda.discovery.model.VersionCursor;
import uk.gov.companieshouse.addresslookup.lambda.shared.acquisition.AcquisitionProperties;
import uk.gov.companieshouse.addresslookup.lambda.shared.acquisition.Commands;
import uk.gov.companieshouse.addresslookup.lambda.shared.os.OsClient;
import uk.gov.companieshouse.addresslookup.lambda.test.utils.TestUtils;
import uk.gov.companieshouse.release.model.*;

import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;
import static org.mockito.Mockito.lenient;
import static uk.gov.companieshouse.addresslookup.lambda.shared.runtime.RuntimeSupport.JSON;
import static uk.gov.companieshouse.addresslookup.lambda.test.utils.TestUtils.*;
import static uk.gov.companieshouse.release.model.DetailType.DOWNLOAD;

@ExtendWith(MockitoExtension.class)
class DiscoveryServiceTest {

  private static final String COU_REQUEST_JSON =
      """
              {"mode":"COU"}
      """;

  private static final String FULL_REQUEST_JSON =
      """
      {
        "mode":"FULL",
        "versions": {
            "data_package": "20780",
            "version_id": "130989"
        }
      }
      """;

  private static final String SECOND_PACKAGE_CURSOR_JSON =
      """
      {
        "dataPackageId": "second",
        "lastProcessedVersionId": "150035",
        "lastProcessedDate": "2026-10-05"
      }
      """;

  private static final String TEST_CURSOR_JSON =
      """
      {
        "dataPackageId": "20780",
        "lastProcessedVersionId": "150035",
        "lastProcessedDate": "2026-10-05"
      }
      """;

  private static final String TEST_COU_UPDATE_CURSOR_JSON =
      """
        {
          "dataPackageId": "20780",
          "lastProcessedVersionId": "150104",
          "lastProcessedDate": "2026-10-06"
        }
      """;

  private static final String TEST_FULL_UPDATE_CURSOR_JSON =
      """
        {
          "dataPackageId": "20780",
          "lastProcessedVersionId": "130989",
          "lastProcessedDate": "2026-05-01"
        }
      """;

  private static final String CONTROL_BUCKET = "control-bucket",
      LANDING_BUCKET = "landing-bucket",
      CURSOR_KEY = "cursors/20780.json";

  private static final String TEST_RELEASE_ID = "12345678-1234-1234-1234-123456789abc";

  private DiscoveryService discoveryService;

  @Mock private S3Client s3Client;

  @Mock private OsClient osClient;

  @Mock private Commands commands;

  @Mock private AcquisitionProperties properties;

  @Mock private UUID testUUID;

  @BeforeEach
  void setUp() {
    when(properties.datasets())
        .thenReturn(
            List.of(
                new DatasetDefault("add_gb_royalmailaddress", "2.0"),
                new DatasetDefault("add_isl_royalmailaddress", "2.0")));
    lenient()
        .when(properties.feedForPackage("20780"))
        .thenReturn(
            uk.gov.companieshouse.addresslookup.lambda.shared.util.KeyLayout.Feed.INITIAL_FULL);
    lenient()
        .when(
            properties.packagesForFeed(
                uk.gov.companieshouse.addresslookup.lambda.shared.util.KeyLayout.Feed.DAILY_COU))
        .thenReturn(List.of("20780"));

    discoveryService = new DiscoveryService(s3Client, osClient, commands, properties);
  }

  @Test
  void continuesToNextPackageWhenFirstPackageFails() throws Exception {
    when(properties.packagesForFeed(
        uk.gov.companieshouse.addresslookup.lambda.shared.util.KeyLayout.Feed.DAILY_COU))
        .thenReturn(List.of("first", "second"));
    when(properties.controlBucket()).thenReturn(CONTROL_BUCKET);
    when(properties.osHttpURLTemplate())
        .thenReturn("https://api.os.uk/downloads/v1/dataPackages/{dataPackage}/versions/{version}");
    when(s3Client.getObject(any(GetObjectRequest.class)))
        .thenThrow(new IllegalStateException("Missing cursor"))
        .thenReturn(TestUtils.createResponseInputStream(SECOND_PACKAGE_CURSOR_JSON));

    var failure = assertThrows(
        IllegalStateException.class, () -> discoveryService.discover(COU_REQUEST_JSON));

    assertEquals(1, failure.getSuppressed().length);
    assertEquals("Discovery failed for package first", failure.getSuppressed()[0].getMessage());
    verify(s3Client).getObject(GetObjectRequest.builder()
        .bucket(CONTROL_BUCKET).key("cursors/first.json").build());
    verify(s3Client).getObject(GetObjectRequest.builder()
        .bucket(CONTROL_BUCKET).key("cursors/second.json").build());
    verify(osClient).json("https://api.os.uk/downloads/v1/dataPackages/second/versions/150035");
  }

  @Test
  void returnsResultsForAllDailyPackages() throws Exception {
    when(properties.packagesForFeed(
        uk.gov.companieshouse.addresslookup.lambda.shared.util.KeyLayout.Feed.DAILY_COU))
        .thenReturn(List.of("first", "second"));
    when(properties.controlBucket()).thenReturn(CONTROL_BUCKET);
    when(properties.osHttpURLTemplate())
        .thenReturn("https://api.os.uk/downloads/v1/dataPackages/{dataPackage}/versions/{version}");
    when(s3Client.getObject(any(GetObjectRequest.class)))
        .thenAnswer(call -> {
          String packageId =
              call.getArgument(0, GetObjectRequest.class).key().replace("cursors/", "")
                  .replace(".json", "");
          return TestUtils.createResponseInputStream(
              TEST_CURSOR_JSON.replace("\"20780\"", "\"" + packageId + "\""));
        });

    assertEquals("No Next Version URL,No Next Version URL",
        discoveryService.discover(COU_REQUEST_JSON));
  }

  @Test
  void rejectsCursorForDifferentConfiguredPackage() throws Exception {
    when(properties.packagesForFeed(
        uk.gov.companieshouse.addresslookup.lambda.shared.util.KeyLayout.Feed.DAILY_COU))
        .thenReturn(List.of("first"));
    when(properties.controlBucket()).thenReturn(CONTROL_BUCKET);
    when(s3Client.getObject(any(GetObjectRequest.class)))
        .thenReturn(TestUtils.createResponseInputStream(TEST_CURSOR_JSON));

    var failure =
        assertThrows(
            IllegalStateException.class, () -> discoveryService.discover(COU_REQUEST_JSON));

    assertEquals(1, failure.getSuppressed().length);
    assertEquals(
        "Cursor package ID does not match configured package",
        failure.getSuppressed()[0].getCause().getMessage());
    verifyNoInteractions(osClient);
  }

  @Test
  void shouldReturnReleaseIdForCOURequestValidDatasetAndSuccessfullySent() throws Exception {

    GetObjectRequest request =
        GetObjectRequest.builder().bucket(CONTROL_BUCKET).key(CURSOR_KEY).build();
    PutObjectRequest updateCursorRequest =
        PutObjectRequest.builder()
            .bucket(CONTROL_BUCKET)
            .key(CURSOR_KEY)
            .contentType("application/json")
            .build();
    PutObjectRequest saveManifestRequest =
        PutObjectRequest.builder()
            .bucket(LANDING_BUCKET)
            .key("daily_cou/20780/150104/manifest/data_package_manifest.json")
            .contentType("application/json")
            .build();

    String expectedPlanKey = "daily_cou/20780/150104/manifest/data_package_manifest.json";

    S3PutRequestBodyMatcher<VersionCursor> cursorUpdateMatcher =
        new S3PutRequestBodyMatcher<>(TEST_COU_UPDATE_CURSOR_JSON, VersionCursor.class);

    S3PutReleaseManifestRequestBodyMatcher manifestMatcher =
        new S3PutReleaseManifestRequestBodyMatcher(
            ReleaseManifest.read(
                JSON.readTree(readFileFromClasspath("output/output-manifest-COU.json"))));

    when(properties.controlBucket()).thenReturn(CONTROL_BUCKET);
    when(properties.landingBucket()).thenReturn(LANDING_BUCKET);
    when(properties.osHttpURLTemplate())
        .thenReturn("https://api.os.uk/downloads/v1/dataPackages/{dataPackage}/versions/{version}");

    when(testUUID.toString()).thenReturn(TEST_RELEASE_ID);

    when(s3Client.getObject(request))
        .thenReturn(TestUtils.createResponseInputStream(TEST_CURSOR_JSON));

    when(osClient.json("https://api.os.uk/downloads/v1/dataPackages/20780/versions/150035"))
        .thenReturn(
            jsonFileToJsonNode(
                "OsData/COU/previous-data-package-20780-version-150035-data.json", JSON));

    when(osClient.json("https://api.os.uk/downloads/v1/dataPackages/20780/versions/150104"))
        .thenReturn(
            jsonFileToJsonNode(
                "OsData/COU/latest-data-package-20780-version-150104-data.json", JSON));

    when(osClient.json(
            "https://api.os.uk/downloads/v1/dataPackages/20780/versions/150104/downloads?fileName=add_gb_royalmailaddress_orderSummary.json"))
        .thenReturn(
            jsonFileToJsonNode("OsData/COU/add_gb_royalmailaddress_orderSummary.json", JSON));

    when(osClient.json(
            "https://api.os.uk/downloads/v1/dataPackages/20780/versions/150104/downloads?fileName=add_isl_royalmailaddress_orderSummary.json"))
        .thenReturn(
            jsonFileToJsonNode("OsData/COU/add_isl_royalmailaddress_orderSummary.json", JSON));

    String releaseId;
    try (MockedStatic<UUID> mockedUUID = mockStatic(UUID.class)) {
      mockedUUID.when(() -> UUID.nameUUIDFromBytes(any())).thenReturn(testUUID);
      mockedUUID.when(() -> UUID.fromString(anyString())).thenCallRealMethod();
      releaseId = discoveryService.discover(COU_REQUEST_JSON);
    }

    verify(s3Client).getObject(request);
    verify(s3Client).putObject(eq(updateCursorRequest), argThat(cursorUpdateMatcher));
    verify(s3Client).putObject(eq(saveManifestRequest), argThat(manifestMatcher));
    verifyOrderSummarySaved("daily_cou/20780/150104", "add_gb_royalmailaddress");
    verifyOrderSummarySaved("daily_cou/20780/150104", "add_isl_royalmailaddress");
    verify(osClient).json("https://api.os.uk/downloads/v1/dataPackages/20780/versions/150035");
    verify(osClient).json("https://api.os.uk/downloads/v1/dataPackages/20780/versions/150104");
    verify(osClient)
        .json(
            "https://api.os.uk/downloads/v1/dataPackages/20780/versions/150104/downloads?fileName=add_gb_royalmailaddress_orderSummary.json");
    verify(osClient)
        .json(
            "https://api.os.uk/downloads/v1/dataPackages/20780/versions/150104/downloads?fileName=add_isl_royalmailaddress_orderSummary.json");

    verify(commands).send(DOWNLOAD.name(), expectedPlanKey, "add_gb_royalmailaddress");
    verify(commands).send(DOWNLOAD.name(), expectedPlanKey, "add_isl_royalmailaddress");
    assertEquals(TEST_RELEASE_ID, releaseId);
  }

  @Test
  void shouldReturnReleaseIdForFULLRequestValidDatasetAndSuccessfullySent() throws Exception {
    PutObjectRequest updateCursorRequest =
        PutObjectRequest.builder()
            .bucket(CONTROL_BUCKET)
            .key(CURSOR_KEY)
            .contentType("application/json")
            .build();
    PutObjectRequest saveManifestRequest =
        PutObjectRequest.builder()
            .bucket(LANDING_BUCKET)
            .key("initial_full/20780/130989/manifest/data_package_manifest.json")
            .contentType("application/json")
            .build();

    String expectedPlanKey = "initial_full/20780/130989/manifest/data_package_manifest.json";

    S3PutRequestBodyMatcher<VersionCursor> cursorUpdateMatcher =
        new S3PutRequestBodyMatcher<>(TEST_FULL_UPDATE_CURSOR_JSON, VersionCursor.class);

    S3PutReleaseManifestRequestBodyMatcher manifestMatcher =
        new S3PutReleaseManifestRequestBodyMatcher(
            ReleaseManifest.read(
                JSON.readTree(readFileFromClasspath("output/output-manifest-Full.json"))));

    when(properties.controlBucket()).thenReturn(CONTROL_BUCKET);
    when(properties.landingBucket()).thenReturn(LANDING_BUCKET);
    when(properties.osHttpURLTemplate())
        .thenReturn("https://api.os.uk/downloads/v1/dataPackages/{dataPackage}/versions/{version}");

    when(testUUID.toString()).thenReturn(TEST_RELEASE_ID);

    when(osClient.json("https://api.os.uk/downloads/v1/dataPackages/20780/versions/130989"))
        .thenReturn(
            jsonFileToJsonNode(
                "OsData/Full/full-data-package-20780-version-130989-data.json", JSON));

    when(osClient.json(
            "https://api.os.uk/downloads/v1/dataPackages/20780/versions/130989/downloads?fileName=add_gb_royalmailaddress_orderSummary.json"))
        .thenReturn(
            jsonFileToJsonNode("OsData/Full/add_gb_royalmailaddress_orderSummary.json", JSON));

    when(osClient.json(
            "https://api.os.uk/downloads/v1/dataPackages/20780/versions/130989/downloads?fileName=add_isl_royalmailaddress_orderSummary.json"))
        .thenReturn(
            jsonFileToJsonNode("OsData/Full/add_isl_royalmailaddress_orderSummary.json", JSON));

    String releaseId;
    try (MockedStatic<UUID> mockedUUID = mockStatic(UUID.class)) {
      mockedUUID.when(() -> UUID.nameUUIDFromBytes(any())).thenReturn(testUUID);
      mockedUUID.when(() -> UUID.fromString(anyString())).thenCallRealMethod();
      releaseId =
          discoveryService.discover(
              Map.<String, Object>of(
                  "mode",
                  "FULL",
                  "versions",
                  Map.of("data_package", "20780", "version_id", "130989")));
    }

    verify(osClient).json("https://api.os.uk/downloads/v1/dataPackages/20780/versions/130989");
    verify(osClient)
        .json(
            "https://api.os.uk/downloads/v1/dataPackages/20780/versions/130989/downloads?fileName=add_gb_royalmailaddress_orderSummary.json");
    verify(osClient)
        .json(
            "https://api.os.uk/downloads/v1/dataPackages/20780/versions/130989/downloads?fileName=add_isl_royalmailaddress_orderSummary.json");

    verify(s3Client).putObject(eq(updateCursorRequest), argThat(cursorUpdateMatcher));
    verify(s3Client).putObject(eq(saveManifestRequest), argThat(manifestMatcher));
    verifyOrderSummarySaved("initial_full/20780/130989", "add_gb_royalmailaddress");
    verifyOrderSummarySaved("initial_full/20780/130989", "add_isl_royalmailaddress");

    verify(commands).send(DOWNLOAD.name(), expectedPlanKey, "add_gb_royalmailaddress");
    verify(commands).send(DOWNLOAD.name(), expectedPlanKey, "add_isl_royalmailaddress");
    assertEquals(TEST_RELEASE_ID, releaseId);
  }

  private void verifyOrderSummarySaved(String prefix, String dataset) {
    verify(s3Client)
        .putObject(
            argThat((PutObjectRequest request) ->
                request.bucket().equals(LANDING_BUCKET)
                    && request.key().equals(prefix + "/downloads/" + dataset + "_orderSummary.json")
                    && request.ifNoneMatch() == null),
            any(RequestBody.class));
  }

  @Test
  void shouldReturnNoCommonContiguousReleaseForCOURequestValidDatasetAndSuccessfullySent()
      throws Exception {

    GetObjectRequest request =
        GetObjectRequest.builder().bucket(CONTROL_BUCKET).key(CURSOR_KEY).build();

    when(properties.controlBucket()).thenReturn(CONTROL_BUCKET);
    when(properties.osHttpURLTemplate())
        .thenReturn("https://api.os.uk/downloads/v1/dataPackages/{dataPackage}/versions/{version}");

    when(s3Client.getObject(request))
        .thenReturn(TestUtils.createResponseInputStream(TEST_CURSOR_JSON));

    when(osClient.json("https://api.os.uk/downloads/v1/dataPackages/20780/versions/150035"))
        .thenReturn(
            jsonFileToJsonNode(
                "OsData/COU/previous-data-package-20780-version-150035-data.json", JSON));

    when(osClient.json("https://api.os.uk/downloads/v1/dataPackages/20780/versions/150104"))
        .thenReturn(
            jsonFileToJsonNode(
                "OsData/COU/latest-data-package-20780-version-150104-data.json", JSON));

    when(osClient.json(
            "https://api.os.uk/downloads/v1/dataPackages/20780/versions/150104/downloads?fileName=add_gb_royalmailaddress_orderSummary.json"))
        .thenReturn(
            jsonFileToJsonNode(
                "OsData/COU/add_gb_royalmailaddress_orderSummary_incorrect_target_date.json",
                JSON));

    when(osClient.json(
            "https://api.os.uk/downloads/v1/dataPackages/20780/versions/150104/downloads?fileName=add_isl_royalmailaddress_orderSummary.json"))
        .thenReturn(
            jsonFileToJsonNode("OsData/COU/add_isl_royalmailaddress_orderSummary.json", JSON));

    String response = discoveryService.discover(COU_REQUEST_JSON);

    assertEquals("NO_COMMON_CONTIGUOUS_RELEASE", response);

    verify(s3Client).getObject(request);
    verify(osClient).json("https://api.os.uk/downloads/v1/dataPackages/20780/versions/150035");
    verify(osClient).json("https://api.os.uk/downloads/v1/dataPackages/20780/versions/150104");
    verify(osClient)
        .json(
            "https://api.os.uk/downloads/v1/dataPackages/20780/versions/150104/downloads?fileName=add_gb_royalmailaddress_orderSummary.json");
    verify(osClient)
        .json(
            "https://api.os.uk/downloads/v1/dataPackages/20780/versions/150104/downloads?fileName=add_isl_royalmailaddress_orderSummary.json");
  }

  @Test
  void shouldReturnNoCommonContiguousReleaseForFULLRequestDifferentToDates() throws Exception {

    when(osClient.json("https://api.os.uk/downloads/v1/dataPackages/20780/versions/130989"))
        .thenReturn(
            jsonFileToJsonNode(
                "OsData/Full/full-data-package-20780-version-130989-data.json", JSON));

    when(osClient.json(
            "https://api.os.uk/downloads/v1/dataPackages/20780/versions/130989/downloads?fileName=add_gb_royalmailaddress_orderSummary.json"))
        .thenReturn(
            jsonFileToJsonNode(
                "OsData/Full/add_gb_royalmailaddress_orderSummary_incorrect_target_date.json",
                JSON));

    when(osClient.json(
            "https://api.os.uk/downloads/v1/dataPackages/20780/versions/130989/downloads?fileName=add_isl_royalmailaddress_orderSummary.json"))
        .thenReturn(
            jsonFileToJsonNode("OsData/Full/add_isl_royalmailaddress_orderSummary.json", JSON));
    when(properties.osHttpURLTemplate())
        .thenReturn("https://api.os.uk/downloads/v1/dataPackages/{dataPackage}/versions/{version}");

    String response = discoveryService.discover(FULL_REQUEST_JSON);

    assertEquals("NO_COMMON_CONTIGUOUS_RELEASE", response);

    verify(osClient).json("https://api.os.uk/downloads/v1/dataPackages/20780/versions/130989");
    verify(osClient)
        .json(
            "https://api.os.uk/downloads/v1/dataPackages/20780/versions/130989/downloads?fileName=add_gb_royalmailaddress_orderSummary.json");
    verify(osClient)
        .json(
            "https://api.os.uk/downloads/v1/dataPackages/20780/versions/130989/downloads?fileName=add_isl_royalmailaddress_orderSummary.json");
  }

  @Test
  void shouldThrowExceptionInvalidMode() {
    try {
      discoveryService.discover("{\"mode\":\"FRED\"}");
      fail("Should have thrown an exception");
    } catch (Throwable throwable) {
      assertEquals("Unknown mode", throwable.getMessage());
    }
  }

  @Test
  void shouldThrowExceptionForCOUWithNoCursor() {

    when(properties.controlBucket()).thenReturn(CONTROL_BUCKET);

    try {
      discoveryService.discover(COU_REQUEST_JSON);
      fail("Should have thrown an exception");
    } catch (Throwable throwable) {
      assertEquals(
          "The active.json Cursor file could not be found in the S3 bucket.",
          throwable.getSuppressed()[0].getCause().getMessage());
      verify(s3Client)
          .getObject(GetObjectRequest.builder().bucket(CONTROL_BUCKET).key(CURSOR_KEY).build());
    }
  }

  @Test
  void shouldThrowExceptionForFULLWithMissingDataPackageParameter() {

    try {
      discoveryService.discover(
          """
          {
            "mode":"FULL",
            "versions": {
                "version_id": "130989"
            }
          }
          """);
      fail("Should have thrown an exception");
    } catch (Throwable throwable) {
      assertEquals("Missing data_package", throwable.getMessage());
    }
  }

  @Test
  void shouldThrowExceptionForFULLWithMissingVersionIdParameter() {
    try {
      discoveryService.discover(
          """
          {
            "mode":"FULL",
            "versions": {
                "data_package": "130989"
            }
          }
          """);
      fail("Should have thrown an exception");
    } catch (Throwable throwable) {
      assertEquals("Missing version_id", throwable.getMessage());
    }
  }

  @Test
  void shouldThrowExceptionForFULLWithMissingVersionsParameter() {
    try {
      discoveryService.discover(
          """
          {
            "mode":"FULL"
          }
          """);
      fail("Should have thrown an exception");
    } catch (Throwable throwable) {
      assertEquals("versions node missing from request for FULL mode", throwable.getMessage());
    }
  }

  @Test
  void shouldThrowExceptionNoPackageVersionDataReturnedFromOS() throws Exception {

    GetObjectRequest request =
        GetObjectRequest.builder().bucket(CONTROL_BUCKET).key(CURSOR_KEY).build();

    when(properties.controlBucket()).thenReturn(CONTROL_BUCKET);

    when(properties.osHttpURLTemplate())
        .thenReturn("https://api.os.uk/downloads/v1/dataPackages/{dataPackage}/versions/{version}");

    when(s3Client.getObject(request))
        .thenReturn(TestUtils.createResponseInputStream(TEST_CURSOR_JSON));

    when(osClient.json("https://api.os.uk/downloads/v1/dataPackages/20780/versions/150035"))
        .thenReturn(
            jsonFileToJsonNode(
                "OsData/COU/previous-data-package-20780-version-150035-data.json", JSON));

    try {
      discoveryService.discover(COU_REQUEST_JSON);
    } catch (Throwable throwable) {
      assertEquals(
          "No Package Version Data for [https://api.os.uk/downloads/v1/dataPackages/20780/versions/150104]",
          throwable.getSuppressed()[0].getCause().getMessage());
    }
    verify(s3Client).getObject(request);
    verify(osClient).json("https://api.os.uk/downloads/v1/dataPackages/20780/versions/150035");
    verify(osClient).json("https://api.os.uk/downloads/v1/dataPackages/20780/versions/150104");
  }

  @Test
  void testModel() throws Exception {

    PackageVersionData packageVersionData =
        jsonFileToObject(
            "OsData/COU/previous-data-package-20780-version-150035-data.json",
            PackageVersionData.class,
            JSON);

    assertNotNull(packageVersionData);

    OrderSummary orderSummary =
        jsonFileToObject(
            "OsData/COU/add_gb_builtaddress_orderSummary.json", OrderSummary.class, JSON);

    assertNotNull(orderSummary);

    VersionCursor cursor = JSON.readValue(TEST_CURSOR_JSON, VersionCursor.class);
    assertNotNull(cursor);
  }

  @Test
  void testCOUManifestCreation() throws Exception {
    ReleaseJson.MAPPER.registerModule(new JavaTimeModule());
    ReleaseManifest manifest =
        ReleaseManifest.read(jsonFileToJsonNode("output/output-manifest-COU.json", JSON));

    assertNotNull(manifest);
  }

  @Test
  void testFullManifestCreation() throws Exception {
    ReleaseJson.MAPPER.registerModule(new JavaTimeModule());
    ReleaseManifest manifest =
        ReleaseManifest.read(jsonFileToJsonNode("output/output-manifest-Full.json", JSON));

    assertNotNull(manifest);
  }

  private static class S3PutRequestBodyMatcher<T> implements ArgumentMatcher<RequestBody> {

    private final String expectedBodyJson;
    private final Class<T> clazz;

    S3PutRequestBodyMatcher(String expectedBodyJson, Class<T> clazz) {
      this.expectedBodyJson = expectedBodyJson;
      this.clazz = clazz;
    }

    @Override
    public boolean matches(RequestBody requestBody) {
      boolean valid = false;
      try {
        T expected = JSON.readValue(expectedBodyJson, clazz);
        T test = JSON.readValue(requestBody.contentStreamProvider().newStream(), clazz);
        if (expected != null && expected.equals(test)) {
          valid = true;
        }
      } catch (Exception exception) {
        exception.printStackTrace();
        return false;
      }
      return valid;
    }

    @Override
    public Class<RequestBody> type() {
      return RequestBody.class;
    }
  }

  private static class S3PutReleaseManifestRequestBodyMatcher
      implements ArgumentMatcher<RequestBody> {

    private final ReleaseManifest expectedManifest;

    S3PutReleaseManifestRequestBodyMatcher(ReleaseManifest expectedManifest) {
      this.expectedManifest = expectedManifest;
    }

    @Override
    public boolean matches(RequestBody requestBody) {
      boolean valid = false;
      try {
        ReleaseManifest test =
            ReleaseManifest.read(JSON.readTree(requestBody.contentStreamProvider().newStream()));
        if (expectedManifest != null) {
          assertEquals(expectedManifest.json(), test.json());
        }
        valid = true;
      } catch (Exception exception) {
        exception.printStackTrace();
      }
      return valid;
    }

    @Override
    public Class<RequestBody> type() {
      return RequestBody.class;
    }
  }
}
