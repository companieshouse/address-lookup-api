package uk.gov.companieshouse.addresslookup.lamda.shared.acquisition;

import com.amazonaws.services.lambda.runtime.Context;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.junit.jupiter.api.Test;
import software.amazon.awssdk.core.ResponseInputStream;
import software.amazon.awssdk.core.sync.RequestBody;
import software.amazon.awssdk.http.AbortableInputStream;
import software.amazon.awssdk.services.s3.S3Client;
import software.amazon.awssdk.services.s3.model.*;
import uk.gov.companieshouse.addresslookup.lamda.shared.storage.ReleaseStore;

import java.io.ByteArrayInputStream;
import java.io.InputStream;
import java.lang.reflect.Proxy;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.Duration;
import java.util.*;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Consumer;

import static org.junit.jupiter.api.Assertions.*;
import static uk.gov.companieshouse.addresslookup.lamda.shared.runtime.RuntimeSupport.JSON;

final class DownloadServiceTest {
  private static final String BUCKET = "source-bucket";
  private static final String PLAN_KEY =
      "acquisitions/2026-05-16/4b845f1a-b0dc-33f1-b76b-b76bb7cd0c56.json";
  private static final String RELEASE_ID = "4b845f1a-b0dc-33f1-b76b-b76bb7cd0c56";
  private static final String DATASET = "add_isl_royalmailaddress";

  @Test
  void downloadsOnlyTheDatasetNamedByTheEventBridgeCommand() throws Exception {
    byte[] zip = "local test zip bytes".getBytes(StandardCharsets.UTF_8);
    JsonNode manifest = manifestWithSelectedMd5(md5(zip));
    var s3 = new FakeS3();
    s3.objects.put(PLAN_KEY, JSON.writeValueAsBytes(manifest));
    var requestedUrl = new AtomicReference<String>();
    var service =
        new DownloadService(
            s3.client(),
            url -> {
              requestedUrl.set(url);
              return new ByteArrayInputStream(zip);
            },
            properties());

    assertEquals("ZIP_SCAN", service.download(event(), context()));

    String expectedUrl = manifest.path(DATASET).path("zip").path("url").asText();
    assertEquals(expectedUrl, requestedUrl.get());
    assertEquals(1, s3.completedKeys.stream().filter(k -> k.contains("/" + DATASET + "/")).count());
    assertTrue(s3.completedKeys.stream().noneMatch(k -> k.contains("add_gb_builtaddress")));
    assertEquals(
        "Scanned/" + RELEASE_ID + "/" + DATASET + ".receipt.json",
        ReleaseStore.receiptKey(RELEASE_ID, DATASET, "download"));
    assertTrue(s3.objects.containsKey(ReleaseStore.receiptKey(RELEASE_ID, DATASET, "download")));
    JsonNode receipt =
        JSON.readTree(s3.objects.get(ReleaseStore.receiptKey(RELEASE_ID, DATASET, "download")));
    assertEquals(md5(zip), receipt.path("md5").asText());
    assertEquals(zip.length, receipt.path("bytes").asLong());
    assertTrue(receipt.path("key").asText().startsWith("quarantine/zips/" + RELEASE_ID + "/" + DATASET + "/"));
  }

  @Test
  void checksumMismatchAbortsUploadAndDoesNotPublishReceipt() throws Exception {
    var s3 = new FakeS3();
    s3.objects.put(PLAN_KEY, fixtureBytes("4b845f1a-b0dc-33f1-b76b-b76bb7cd0c56.json"));
    var service =
        new DownloadService(
            s3.client(),
            url -> new ByteArrayInputStream("wrong content".getBytes(StandardCharsets.UTF_8)),
            properties());

    assertEquals("CHECKSUM_MISMATCH", service.download(event(), context()));

    assertTrue(s3.abortedUploads > 0, "multipart upload should be aborted");
    assertFalse(
        s3.objects.containsKey(ReleaseStore.receiptKey(RELEASE_ID, DATASET, "download")),
        "checksum mismatch must not publish a receipt");
    assertTrue(s3.completedKeys.isEmpty(), "checksum mismatch must not complete a ZIP object");
  }

  @Test
  void existingReceiptMakesDownloadIdempotent() throws Exception {
    var s3 = new FakeS3();
    s3.objects.put(PLAN_KEY, fixtureBytes("4b845f1a-b0dc-33f1-b76b-b76bb7cd0c56.json"));
    s3.objects.put(
        ReleaseStore.receiptKey(RELEASE_ID, DATASET, "download"),
        JSON.writeValueAsBytes(
            Map.of(
                "key", "quarantine/zips/" + RELEASE_ID + "/" + DATASET + "/already.zip",
                "version", "v1",
                "sha256", "sha",
                "md5", "17db768ba6b7a85c5e34d5e200930ff5",
                "bytes", 1)));
    var called = new AtomicReference<Boolean>(false);
    var service =
        new DownloadService(
            s3.client(),
            url -> {
              called.set(true);
              return InputStream.nullInputStream();
            },
            properties());

    assertEquals("SKIPPED", service.download(event(), context()));
    assertFalse(called.get(), "existing receipt should avoid another OS download");
  }

  private static AcquisitionProperties properties() {
    return new AcquisitionProperties(
        BUCKET,
        "scanned-bucket",
        "event-bus",
        "redacted",
        "{}",
        512L * 1024L * 1024L,
        1024L * 1024L * 1024L,
        20,
        Duration.ofSeconds(20),
        Duration.ofSeconds(840));
  }

  private static Map<String, Object> event() throws Exception {
    return JSON.readValue(fixtureBytes("mock-eventbridge-example.json"), new TypeReference<>() {});
  }

  private static JsonNode manifestWithSelectedMd5(String md5) throws Exception {
    ObjectNode manifest =
        (ObjectNode) JSON.readTree(fixtureBytes("4b845f1a-b0dc-33f1-b76b-b76bb7cd0c56.json"));
    ((ObjectNode) manifest.path(DATASET).path("zip")).put("md5", md5);
    return manifest;
  }

  private static byte[] fixtureBytes(String name) throws Exception {
    try (var in = DownloadServiceTest.class.getResourceAsStream("/ALS-44/" + name)) {
      assertNotNull(in, "Missing fixture " + name);
      return in.readAllBytes();
    }
  }

  private static Context context() {
    return (Context)
        Proxy.newProxyInstance(
            Context.class.getClassLoader(),
            new Class<?>[] {Context.class},
            (proxy, method, args) ->
                switch (method.getName()) {
                  case "getRemainingTimeInMillis" -> 900_000;
                  case "getMemoryLimitInMB" -> 1024;
                  default -> null;
                });
  }

  private static String md5(byte[] bytes) throws Exception {
    return HexFormat.of().formatHex(MessageDigest.getInstance("MD5").digest(bytes));
  }

  private static final class FakeS3 {
    final Map<String, byte[]> objects = new LinkedHashMap<>();
    final Map<String, Upload> uploads = new HashMap<>();
    final List<String> completedKeys = new ArrayList<>();
    int uploadCounter;
    int abortedUploads;

    S3Client client() {
      return (S3Client)
          Proxy.newProxyInstance(
              S3Client.class.getClassLoader(),
              new Class<?>[] {S3Client.class},
              (proxy, method, args) -> invoke(method.getName(), args));
    }

    private Object invoke(String method, Object[] args) throws Exception {
      return switch (method) {
        case "serviceName" -> "s3";
        case "close" -> null;
        case "listObjectsV2" -> listObjects((ListObjectsV2Request) args[0]);
        case "getObject" -> getObject((GetObjectRequest) args[0]);
        case "createMultipartUpload" -> createMultipartUpload(createMultipartUploadRequest(args[0]));
        case "uploadPart" -> uploadPart(uploadPartRequest(args[0]), (RequestBody) args[1]);
        case "completeMultipartUpload" -> completeMultipartUpload(completeMultipartUploadRequest(args[0]));
        case "abortMultipartUpload" -> abortMultipartUpload(abortMultipartUploadRequest(args[0]));
        case "putObject" -> putObject(putObjectRequest(args[0]), (RequestBody) args[1]);
        default -> throw new UnsupportedOperationException(method);
      };
    }

    private ListObjectsV2Response listObjects(ListObjectsV2Request request) {
      var matches =
          objects.keySet().stream()
              .filter(k -> k.startsWith(request.prefix()))
              .map(k -> S3Object.builder().key(k).build())
              .toList();
      return ListObjectsV2Response.builder().contents(matches).build();
    }

    private ResponseInputStream<GetObjectResponse> getObject(GetObjectRequest request) {
      byte[] bytes = objects.get(request.key());
      if (bytes == null)
        throw S3Exception.builder().statusCode(404).message("Not found").build();
      return new ResponseInputStream<>(
          GetObjectResponse.builder().build(),
          AbortableInputStream.create(new ByteArrayInputStream(bytes)));
    }

    private CreateMultipartUploadResponse createMultipartUpload(CreateMultipartUploadRequest request) {
      String uploadId = "upload-" + (++uploadCounter);
      uploads.put(uploadId, new Upload(request.key()));
      return CreateMultipartUploadResponse.builder().uploadId(uploadId).build();
    }

    private UploadPartResponse uploadPart(UploadPartRequest request, RequestBody body) throws Exception {
      uploads.get(request.uploadId()).parts.put(request.partNumber(), read(body));
      return UploadPartResponse.builder().eTag("etag-" + request.partNumber()).build();
    }

    private CompleteMultipartUploadResponse completeMultipartUpload(
        CompleteMultipartUploadRequest request) {
      Upload upload = uploads.remove(request.uploadId());
      byte[] combined =
          upload.parts.values().stream()
              .reduce(
                  new byte[0],
                  (a, b) -> {
                    byte[] out = Arrays.copyOf(a, a.length + b.length);
                    System.arraycopy(b, 0, out, a.length, b.length);
                    return out;
                  });
      objects.put(upload.key, combined);
      completedKeys.add(upload.key);
      return CompleteMultipartUploadResponse.builder().versionId("version-" + completedKeys.size()).build();
    }

    private AbortMultipartUploadResponse abortMultipartUpload(AbortMultipartUploadRequest request) {
      uploads.remove(request.uploadId());
      abortedUploads++;
      return AbortMultipartUploadResponse.builder().build();
    }

    private PutObjectResponse putObject(PutObjectRequest request, RequestBody body) throws Exception {
      if ("*".equals(request.ifNoneMatch()) && objects.containsKey(request.key()))
        throw S3Exception.builder().statusCode(412).message("exists").build();
      objects.put(request.key(), read(body));
      return PutObjectResponse.builder().versionId("put-version").build();
    }

    private static byte[] read(RequestBody body) throws Exception {
      try (var in = body.contentStreamProvider().newStream()) {
        return in.readAllBytes();
      }
    }

    @SuppressWarnings("unchecked")
    private static CreateMultipartUploadRequest createMultipartUploadRequest(Object arg) {
      if (arg instanceof CreateMultipartUploadRequest request) return request;
      var builder = CreateMultipartUploadRequest.builder();
      ((Consumer<CreateMultipartUploadRequest.Builder>) arg).accept(builder);
      return builder.build();
    }

    @SuppressWarnings("unchecked")
    private static UploadPartRequest uploadPartRequest(Object arg) {
      if (arg instanceof UploadPartRequest request) return request;
      var builder = UploadPartRequest.builder();
      ((Consumer<UploadPartRequest.Builder>) arg).accept(builder);
      return builder.build();
    }

    @SuppressWarnings("unchecked")
    private static CompleteMultipartUploadRequest completeMultipartUploadRequest(Object arg) {
      if (arg instanceof CompleteMultipartUploadRequest request) return request;
      var builder = CompleteMultipartUploadRequest.builder();
      ((Consumer<CompleteMultipartUploadRequest.Builder>) arg).accept(builder);
      return builder.build();
    }

    @SuppressWarnings("unchecked")
    private static AbortMultipartUploadRequest abortMultipartUploadRequest(Object arg) {
      if (arg instanceof AbortMultipartUploadRequest request) return request;
      var builder = AbortMultipartUploadRequest.builder();
      ((Consumer<AbortMultipartUploadRequest.Builder>) arg).accept(builder);
      return builder.build();
    }

    @SuppressWarnings("unchecked")
    private static PutObjectRequest putObjectRequest(Object arg) {
      if (arg instanceof PutObjectRequest request) return request;
      var builder = PutObjectRequest.builder();
      ((Consumer<PutObjectRequest.Builder>) arg).accept(builder);
      return builder.build();
    }

    private record Upload(String key, Map<Integer, byte[]> parts) {
      Upload(String key) {
        this(key, new TreeMap<>());
      }
    }
  }
}
