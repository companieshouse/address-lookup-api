package uk.gov.companieshouse.addresslookup.lambda.discovery.service;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.fail;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.argThat;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mockStatic;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static uk.gov.companieshouse.addresslookup.lambda.shared.runtime.RuntimeSupport.JSON;
import static uk.gov.companieshouse.addresslookup.lambda.test.utils.TestUtils.jsonFileToJsonNode;
import static uk.gov.companieshouse.addresslookup.lambda.test.utils.TestUtils.jsonFileToObject;
import static uk.gov.companieshouse.addresslookup.lambda.test.utils.TestUtils.readFileFromClasspath;
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
import uk.gov.companieshouse.addresslookup.lambda.shared.runtime.RuntimeSupport;
import uk.gov.companieshouse.addresslookup.lambda.test.utils.TestUtils;
import uk.gov.companieshouse.release.model.DatasetDefault;
import uk.gov.companieshouse.release.model.OrderSummary;
import uk.gov.companieshouse.release.model.PackageVersionData;
import uk.gov.companieshouse.release.model.ReleaseJson;
import uk.gov.companieshouse.release.model.ReleaseManifest;

import java.util.List;
import java.util.UUID;

@ExtendWith(MockitoExtension.class)
public class DiscoveryServiceTest {

    private static final String COU_REQUEST_JSON = """    
            {"mode":"COU"}
    """;

    private static final String FULL_REQUEST_JSON = """
     {
       "mode":"FULL",
       "versions": {
           "data_package": "20780",
           "version_id": "130989"
       }
     }
     """;

    private static final String TEST_CURSOR_JSON = """
    {
      "dataPackageId": "20780",
      "lastProcessedVersionId": "150035",
      "lastProcessedDate": "2026-10-05"
    }
    """;

    private static final String TEST_COU_UPDATE_CURSOR_JSON = """
      {
        "dataPackageId": "20780",
        "lastProcessedVersionId": "150104",
        "lastProcessedDate": "2026-10-06"
      }
    """;

    private static final String TEST_FULL_UPDATE_CURSOR_JSON = """
      {
        "dataPackageId": "20780",
        "lastProcessedVersionId": "130989",
        "lastProcessedDate": "2026-05-01"
      }
    """;

    private static final String TEST_SOURCE_BUCKET = "source-bucket",
            CURSOR_KEY = "control/address-release/active.json";

    private static final String TEST_RELEASE_ID = "12345678-1234-1234-1234-123456789abc";

    public DiscoveryService discoveryService;

    @Mock
    private S3Client s3Client;

    @Mock
    private OsClient osClient;

    @Mock
    private Commands commands;

    @Mock
    private AcquisitionProperties properties;

    @Mock
    private UUID testUUID;
    
    @BeforeEach
    public void setUp() {
        when(properties.datasets()).thenReturn(List.of(
                new DatasetDefault("add_gb_royalmailaddress","2.0"),
                new DatasetDefault("add_isl_royalmailaddress","2.0")
        ));

        discoveryService = new DiscoveryService(s3Client, osClient, commands, properties);

    }

    @Test
    public void shouldReturnReleaseIdForCOURequestValidDatasetAndSuccessfullySent() throws Exception {

        GetObjectRequest request = GetObjectRequest.builder()
                .bucket(TEST_SOURCE_BUCKET)
                .key(CURSOR_KEY).build();
        PutObjectRequest updateCursorRequest = PutObjectRequest.builder()
                .bucket(TEST_SOURCE_BUCKET)
                .key(CURSOR_KEY)
                .contentType("application/json")
                .build();
        PutObjectRequest saveManifestRequest = PutObjectRequest.builder()
                .bucket(TEST_SOURCE_BUCKET)
                .key("acquisitions/2026-10-06/" + TEST_RELEASE_ID + ".json")
                .ifNoneMatch("*")
                .contentType("application/json")
                .build();

        String expectedPlanKey = "acquisitions/2026-10-06/" + TEST_RELEASE_ID + ".json";

        S3PutRequestBodyMatcher<VersionCursor> cursorUpdateMatcher = new S3PutRequestBodyMatcher<>(TEST_COU_UPDATE_CURSOR_JSON, VersionCursor.class);

        S3PutReleaseManifestRequestBodyMatcher manifestMatcher = new S3PutReleaseManifestRequestBodyMatcher(ReleaseManifest.read(
                RuntimeSupport.JSON.readTree(
                readFileFromClasspath("output/output-manifest-COU.json"))));

        when(properties.sourceBucket()).thenReturn(TEST_SOURCE_BUCKET);
        when(properties.osHttpURLTemplate())
                .thenReturn("https://api.os.uk/downloads/v1/dataPackages/{dataPackage}/versions/{version}");

        when(testUUID.toString()).thenReturn(TEST_RELEASE_ID);

        when(s3Client.getObject(request))
                .thenReturn(TestUtils.createResponseInputStream(TEST_CURSOR_JSON));

        when(osClient.json("https://api.os.uk/downloads/v1/dataPackages/20780/versions/150035"))
                .thenReturn(jsonFileToJsonNode("OsData/COU/previous-data-package-20780-version-150035-data.json", JSON));

        when(osClient.json("https://api.os.uk/downloads/v1/dataPackages/20780/versions/150104"))
                .thenReturn(jsonFileToJsonNode("OsData/COU/latest-data-package-20780-version-150104-data.json", JSON));

        when(osClient.json("https://api.os.uk/downloads/v1/dataPackages/20780/versions/150104/downloads?fileName=add_gb_royalmailaddress_orderSummary.json"))
                .thenReturn(jsonFileToJsonNode("OsData/COU/add_gb_royalmailaddress_orderSummary.json", JSON));

        when(osClient.json("https://api.os.uk/downloads/v1/dataPackages/20780/versions/150104/downloads?fileName=add_isl_royalmailaddress_orderSummary.json"))
                .thenReturn(jsonFileToJsonNode("OsData/COU/add_isl_royalmailaddress_orderSummary.json", JSON));

        String releaseId;
        try (MockedStatic<UUID> mockedUUID = mockStatic(UUID.class)) {
            mockedUUID.when(() -> UUID.nameUUIDFromBytes(any())).thenReturn(testUUID);
            mockedUUID.when(() -> UUID.fromString(anyString())).thenCallRealMethod();
            releaseId = discoveryService.discover(COU_REQUEST_JSON);
        }

        verify(s3Client).getObject(request);
        verify(s3Client).putObject(eq(updateCursorRequest), argThat(cursorUpdateMatcher));
        verify(s3Client).putObject(eq(saveManifestRequest), argThat(manifestMatcher));
        verify(osClient).json("https://api.os.uk/downloads/v1/dataPackages/20780/versions/150035");
        verify(osClient).json("https://api.os.uk/downloads/v1/dataPackages/20780/versions/150104");
        verify(osClient).json("https://api.os.uk/downloads/v1/dataPackages/20780/versions/150104/downloads?fileName=add_gb_royalmailaddress_orderSummary.json");
        verify(osClient).json("https://api.os.uk/downloads/v1/dataPackages/20780/versions/150104/downloads?fileName=add_isl_royalmailaddress_orderSummary.json");

        verify(commands).send("DOWNLOAD", expectedPlanKey, "add_gb_royalmailaddress");
        verify(commands).send("DOWNLOAD", expectedPlanKey, "add_isl_royalmailaddress");
        assertEquals(TEST_RELEASE_ID, releaseId);

    }

    @Test
    public void shouldReturnReleaseIdForFULLRequestValidDatasetAndSuccessfullySent() throws Exception {
        PutObjectRequest updateCursorRequest = PutObjectRequest.builder()
                .bucket(TEST_SOURCE_BUCKET)
                .key(CURSOR_KEY)
                .contentType("application/json")
                .build();
        PutObjectRequest saveManifestRequest = PutObjectRequest.builder()
                .bucket(TEST_SOURCE_BUCKET)
                .key("acquisitions/2026-05-01/" + TEST_RELEASE_ID + ".json")
                .ifNoneMatch("*")
                .contentType("application/json")
                .build();

        String expectedPlanKey = "acquisitions/2026-05-01/" + TEST_RELEASE_ID + ".json";

        S3PutRequestBodyMatcher<VersionCursor> cursorUpdateMatcher = new S3PutRequestBodyMatcher<>(TEST_FULL_UPDATE_CURSOR_JSON, VersionCursor.class);

        S3PutReleaseManifestRequestBodyMatcher manifestMatcher = new S3PutReleaseManifestRequestBodyMatcher(ReleaseManifest.read(
                JSON.readTree(
                        readFileFromClasspath("output/output-manifest-Full.json"))));

        when(properties.sourceBucket()).thenReturn(TEST_SOURCE_BUCKET);
        when(properties.osHttpURLTemplate())
                .thenReturn("https://api.os.uk/downloads/v1/dataPackages/{dataPackage}/versions/{version}");

        when(testUUID.toString()).thenReturn(TEST_RELEASE_ID);

        when(osClient.json("https://api.os.uk/downloads/v1/dataPackages/20780/versions/130989"))
                .thenReturn(jsonFileToJsonNode("OsData/Full/full-data-package-20780-version-130989-data.json", JSON));

        when(osClient.json("https://api.os.uk/downloads/v1/dataPackages/20780/versions/130989/downloads?fileName=add_gb_royalmailaddress_orderSummary.json"))
                .thenReturn(jsonFileToJsonNode("OsData/Full/add_gb_royalmailaddress_orderSummary.json", JSON));

        when(osClient.json("https://api.os.uk/downloads/v1/dataPackages/20780/versions/130989/downloads?fileName=add_isl_royalmailaddress_orderSummary.json"))
                .thenReturn(jsonFileToJsonNode("OsData/Full/add_isl_royalmailaddress_orderSummary.json", JSON));

        String releaseId;
        try (MockedStatic<UUID> mockedUUID = mockStatic(UUID.class)) {
            mockedUUID.when(() -> UUID.nameUUIDFromBytes(any())).thenReturn(testUUID);
            mockedUUID.when(() -> UUID.fromString(anyString())).thenCallRealMethod();
            releaseId = discoveryService.discover(FULL_REQUEST_JSON);
        }

        verify(osClient).json("https://api.os.uk/downloads/v1/dataPackages/20780/versions/130989");
        verify(osClient).json("https://api.os.uk/downloads/v1/dataPackages/20780/versions/130989/downloads?fileName=add_gb_royalmailaddress_orderSummary.json");
        verify(osClient).json("https://api.os.uk/downloads/v1/dataPackages/20780/versions/130989/downloads?fileName=add_isl_royalmailaddress_orderSummary.json");

        verify(s3Client).putObject(eq(updateCursorRequest), argThat(cursorUpdateMatcher));
        verify(s3Client).putObject(eq(saveManifestRequest), argThat(manifestMatcher));

        verify(commands).send("DOWNLOAD", expectedPlanKey, "add_gb_royalmailaddress");
        verify(commands).send("DOWNLOAD", expectedPlanKey, "add_isl_royalmailaddress");
        assertEquals(TEST_RELEASE_ID, releaseId);

    }

    @Test
    public void shouldReturnNoCommonContiguousReleaseForCOURequestValidDatasetAndSuccessfullySent() throws Exception {

        GetObjectRequest request = GetObjectRequest.builder()
                .bucket(TEST_SOURCE_BUCKET)
                .key(CURSOR_KEY).build();

        when(properties.sourceBucket()).thenReturn(TEST_SOURCE_BUCKET);
        when(properties.osHttpURLTemplate())
                .thenReturn("https://api.os.uk/downloads/v1/dataPackages/{dataPackage}/versions/{version}");

        when(s3Client.getObject(request))
                .thenReturn(TestUtils.createResponseInputStream(TEST_CURSOR_JSON));

        when(osClient.json("https://api.os.uk/downloads/v1/dataPackages/20780/versions/150035"))
                .thenReturn(jsonFileToJsonNode("OsData/COU/previous-data-package-20780-version-150035-data.json", JSON));

        when(osClient.json("https://api.os.uk/downloads/v1/dataPackages/20780/versions/150104"))
                .thenReturn(jsonFileToJsonNode("OsData/COU/latest-data-package-20780-version-150104-data.json", JSON));

        when(osClient.json("https://api.os.uk/downloads/v1/dataPackages/20780/versions/150104/downloads?fileName=add_gb_royalmailaddress_orderSummary.json"))
                .thenReturn(jsonFileToJsonNode("OsData/COU/add_gb_royalmailaddress_orderSummary_incorrect_target_date.json", JSON));

        when(osClient.json("https://api.os.uk/downloads/v1/dataPackages/20780/versions/150104/downloads?fileName=add_isl_royalmailaddress_orderSummary.json"))
                .thenReturn(jsonFileToJsonNode("OsData/COU/add_isl_royalmailaddress_orderSummary.json", JSON));

        String response = discoveryService.discover(COU_REQUEST_JSON);

        assertEquals("NO_COMMON_CONTIGUOUS_RELEASE", response);

        verify(s3Client).getObject(request);
        verify(osClient).json("https://api.os.uk/downloads/v1/dataPackages/20780/versions/150035");
        verify(osClient).json("https://api.os.uk/downloads/v1/dataPackages/20780/versions/150104");
        verify(osClient).json("https://api.os.uk/downloads/v1/dataPackages/20780/versions/150104/downloads?fileName=add_gb_royalmailaddress_orderSummary.json");
        verify(osClient).json("https://api.os.uk/downloads/v1/dataPackages/20780/versions/150104/downloads?fileName=add_isl_royalmailaddress_orderSummary.json");

    }

    @Test
    public void shouldReturnNoCommonContiguousReleaseForFULLRequestDifferentToDates() throws Exception {

        when(osClient.json("https://api.os.uk/downloads/v1/dataPackages/20780/versions/130989"))
                .thenReturn(jsonFileToJsonNode("OsData/Full/full-data-package-20780-version-130989-data.json", JSON));

        when(osClient.json("https://api.os.uk/downloads/v1/dataPackages/20780/versions/130989/downloads?fileName=add_gb_royalmailaddress_orderSummary.json"))
                .thenReturn(jsonFileToJsonNode("OsData/Full/add_gb_royalmailaddress_orderSummary_incorrect_target_date.json", JSON));

        when(osClient.json("https://api.os.uk/downloads/v1/dataPackages/20780/versions/130989/downloads?fileName=add_isl_royalmailaddress_orderSummary.json"))
                .thenReturn(jsonFileToJsonNode("OsData/Full/add_isl_royalmailaddress_orderSummary.json", JSON));
        when(properties.osHttpURLTemplate())
                .thenReturn("https://api.os.uk/downloads/v1/dataPackages/{dataPackage}/versions/{version}");

        String response = discoveryService.discover(FULL_REQUEST_JSON);

        assertEquals("NO_COMMON_CONTIGUOUS_RELEASE", response);

        verify(osClient).json("https://api.os.uk/downloads/v1/dataPackages/20780/versions/130989");
        verify(osClient).json("https://api.os.uk/downloads/v1/dataPackages/20780/versions/130989/downloads?fileName=add_gb_royalmailaddress_orderSummary.json");
        verify(osClient).json("https://api.os.uk/downloads/v1/dataPackages/20780/versions/130989/downloads?fileName=add_isl_royalmailaddress_orderSummary.json");

    }

    @Test
    public void shouldThrowExceptionInvalidMode() {
        try {
            discoveryService.discover("{\"mode\":\"FRED\"}");
            fail("Should have thrown an exception");
        } catch (Throwable throwable) {
            assertEquals("Unknown mode", throwable.getMessage());
        }
    }

    @Test
    public void shouldThrowExceptionForCOUWithNoCursor() {

        when(properties.sourceBucket()).thenReturn(TEST_SOURCE_BUCKET);

        try {
            discoveryService.discover(COU_REQUEST_JSON);
            fail("Should have thrown an exception");
        } catch (Throwable throwable) {
            assertEquals("The active.json Cursor file could not be found in the S3 bucket.", throwable.getMessage());
            verify(s3Client).getObject(GetObjectRequest.builder()
                    .bucket(TEST_SOURCE_BUCKET)
                    .key(CURSOR_KEY).build());
        }
    }

    @Test
    public void shouldThrowExceptionForFULLWithMissingDataPackageParameter() {

        try {
            discoveryService.discover("""
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
    public void shouldThrowExceptionForFULLWithMissingVersionIdParameter() {
        try {
            discoveryService.discover("""
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
    public void shouldThrowExceptionForFULLWithMissingVersionsParameter() {
        try {
            discoveryService.discover("""
     {
       "mode":"FULL"
     }
     """);
            fail("Should have thrown an exception");
        } catch (Throwable throwable) {
            assertEquals("versions node missing from request for FULL mode",
                    throwable.getMessage());
        }
    }

    @Test
    public void shouldThrowExceptionNoPackageVersionDataReturnedFromOS() throws Exception {

        GetObjectRequest request = GetObjectRequest.builder()
                .bucket(TEST_SOURCE_BUCKET)
                .key(CURSOR_KEY).build();

        when(properties.sourceBucket()).thenReturn(TEST_SOURCE_BUCKET);

        when(properties.osHttpURLTemplate())
                .thenReturn("https://api.os.uk/downloads/v1/dataPackages/{dataPackage}/versions/{version}");

        when(s3Client.getObject(request))
                .thenReturn(TestUtils.createResponseInputStream(TEST_CURSOR_JSON));

        when(osClient.json("https://api.os.uk/downloads/v1/dataPackages/20780/versions/150035"))
                .thenReturn(jsonFileToJsonNode("OsData/COU/previous-data-package-20780-version-150035-data.json", JSON));

        try {
            discoveryService.discover(COU_REQUEST_JSON);
        } catch (Throwable throwable) {
            assertEquals("No Package Version Data for [https://api.os.uk/downloads/v1/dataPackages/20780/versions/150104]", throwable.getMessage());
        }
        verify(s3Client).getObject(request);
        verify(osClient).json("https://api.os.uk/downloads/v1/dataPackages/20780/versions/150035");
        verify(osClient).json("https://api.os.uk/downloads/v1/dataPackages/20780/versions/150104");

    }

    @Test
    public void testModel() throws Exception {

        JSON.registerModule(new JavaTimeModule());

        PackageVersionData packageVersionData = jsonFileToObject(
                "OsData/COU/previous-data-package-20780-version-150035-data.json",
                PackageVersionData.class, JSON);

        assertNotNull(packageVersionData);

        OrderSummary orderSummary = jsonFileToObject("OsData/COU/add_gb_builtaddress_orderSummary.json",
                OrderSummary.class, JSON);

        assertNotNull(orderSummary);

        VersionCursor cursor = JSON.readValue(TEST_CURSOR_JSON, VersionCursor.class);
        assertNotNull(cursor);
    }

    @Test
    public void testCOUManifestCreation() throws Exception {
        ReleaseJson.MAPPER.registerModule(new JavaTimeModule());
        ReleaseManifest manifest  = ReleaseManifest.read(jsonFileToJsonNode("output/output-manifest-COU.json", JSON));

        assertNotNull(manifest);
    }

    @Test
    public void testFullManifestCreation() throws Exception {
        ReleaseJson.MAPPER.registerModule(new JavaTimeModule());
        ReleaseManifest manifest  = ReleaseManifest.read(jsonFileToJsonNode("output/output-manifest-Full.json", JSON));

        assertNotNull(manifest);
    }

    private static class S3PutRequestBodyMatcher <T> implements ArgumentMatcher<RequestBody> {

        final private String expectedBodyJson;
        final private Class<T> clazz;

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

    private static class S3PutReleaseManifestRequestBodyMatcher implements ArgumentMatcher<RequestBody> {

        final private ReleaseManifest expectedManifest;

        S3PutReleaseManifestRequestBodyMatcher(ReleaseManifest expectedManifest) {
            this.expectedManifest = expectedManifest;
        }

        @Override
        public boolean matches(RequestBody requestBody) {
            boolean valid = false;
            try {
                ReleaseManifest test = ReleaseManifest.read(JSON.readTree(requestBody.contentStreamProvider().newStream()));
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
