package uk.gov.companieshouse.addresslookup.lambda.shared.testsupport;

import software.amazon.awssdk.core.ResponseInputStream;
import software.amazon.awssdk.core.sync.RequestBody;
import software.amazon.awssdk.http.AbortableInputStream;
import software.amazon.awssdk.services.s3.S3Client;
import software.amazon.awssdk.services.s3.model.AbortMultipartUploadRequest;
import software.amazon.awssdk.services.s3.model.AbortMultipartUploadResponse;
import software.amazon.awssdk.services.s3.model.CompleteMultipartUploadRequest;
import software.amazon.awssdk.services.s3.model.CompleteMultipartUploadResponse;
import software.amazon.awssdk.services.s3.model.CreateMultipartUploadRequest;
import software.amazon.awssdk.services.s3.model.CreateMultipartUploadResponse;
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
import software.amazon.awssdk.services.s3.model.S3Exception;
import software.amazon.awssdk.services.s3.model.S3Object;
import software.amazon.awssdk.services.s3.model.Tag;
import software.amazon.awssdk.services.s3.model.UploadPartRequest;
import software.amazon.awssdk.services.s3.model.UploadPartResponse;

import java.io.ByteArrayInputStream;
import java.lang.reflect.Proxy;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;
import java.util.function.Consumer;

/**
 * Minimal in-memory S3 used by unit tests. Buckets are ignored: objects are addressed by key only.
 * Supports the subset of operations used by the acquisition lambdas.
 */
public final class FakeS3 {
    public final Map<String, byte[]> objects = new LinkedHashMap<>();
    public final Map<String, Upload> uploads = new HashMap<>();
    public final List<String> completedKeys = new ArrayList<>();
    /** GuardDuty scan status by object key; objects without an entry have no scan tag yet. */
    public final Map<String, String> scanStatus = new HashMap<>();
    /** Version id returned by headObject; defaults to {@link #DEFAULT_VERSION}. */
    public final Map<String, String> versions = new HashMap<>();
    public int uploadCounter;
    public int abortedUploads;

    public static final String DEFAULT_VERSION = "v1";

    public S3Client client() {
        return (S3Client)
                Proxy.newProxyInstance(
                        S3Client.class.getClassLoader(),
                        new Class<?>[]{S3Client.class},
                        (proxy, method, args) -> invoke(method.getName(), args));
    }

    private Object invoke(String method, Object[] args) throws Exception {
        return switch (method) {
            case "serviceName" -> "s3";
            case "close" -> null;
            case "listObjectsV2" -> listObjects(listObjectsRequest(args[0]));
            case "getObject" -> getObject(getObjectRequest(args[0]));
            case "headObject" -> headObject(headObjectRequest(args[0]));
            case "getObjectTagging" -> getObjectTagging(getObjectTaggingRequest(args[0]));
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

    private HeadObjectResponse headObject(HeadObjectRequest request) {
        return HeadObjectResponse.builder()
                .versionId(versions.getOrDefault(request.key(), DEFAULT_VERSION))
                .build();
    }

    private GetObjectTaggingResponse getObjectTagging(GetObjectTaggingRequest request) {
        String status = scanStatus.get(request.key());
        var tags =
                status == null
                        ? List.<Tag>of()
                        : List.of(Tag.builder().key("GuardDutyMalwareScanStatus").value(status).build());
        return GetObjectTaggingResponse.builder().tagSet(tags).build();
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
        String version = "version-" + completedKeys.size();
        versions.put(upload.key, version);
        return CompleteMultipartUploadResponse.builder().versionId(version).build();
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
    private static ListObjectsV2Request listObjectsRequest(Object arg) {
        if (arg instanceof ListObjectsV2Request request) return request;
        var builder = ListObjectsV2Request.builder();
        ((Consumer<ListObjectsV2Request.Builder>) arg).accept(builder);
        return builder.build();
    }

    @SuppressWarnings("unchecked")
    private static GetObjectRequest getObjectRequest(Object arg) {
        if (arg instanceof GetObjectRequest request) return request;
        var builder = GetObjectRequest.builder();
        ((Consumer<GetObjectRequest.Builder>) arg).accept(builder);
        return builder.build();
    }

    @SuppressWarnings("unchecked")
    private static HeadObjectRequest headObjectRequest(Object arg) {
        if (arg instanceof HeadObjectRequest request) return request;
        var builder = HeadObjectRequest.builder();
        ((Consumer<HeadObjectRequest.Builder>) arg).accept(builder);
        return builder.build();
    }

    @SuppressWarnings("unchecked")
    private static GetObjectTaggingRequest getObjectTaggingRequest(Object arg) {
        if (arg instanceof GetObjectTaggingRequest request) return request;
        var builder = GetObjectTaggingRequest.builder();
        ((Consumer<GetObjectTaggingRequest.Builder>) arg).accept(builder);
        return builder.build();
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

    public record Upload(String key, Map<Integer, byte[]> parts) {
        Upload(String key) {
            this(key, new TreeMap<>());
        }
    }
}

