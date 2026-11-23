package uk.gov.companieshouse.addresslookup.lambda.shared.storage;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.fasterxml.jackson.databind.ObjectMapper;
import java.io.ByteArrayInputStream;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.HexFormat;
import java.util.Map;
import java.util.function.Consumer;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import software.amazon.awssdk.core.ResponseInputStream;
import software.amazon.awssdk.http.AbortableInputStream;
import software.amazon.awssdk.services.s3.S3Client;
import software.amazon.awssdk.services.s3.model.AbortMultipartUploadResponse;
import software.amazon.awssdk.services.s3.model.GetObjectRequest;
import software.amazon.awssdk.services.s3.model.GetObjectResponse;
import software.amazon.awssdk.services.s3.model.HeadObjectRequest;
import software.amazon.awssdk.services.s3.model.HeadObjectResponse;
import software.amazon.awssdk.services.s3.model.S3Exception;

final class ExistingObjectPolicyTest {
  private static final ObjectTarget TARGET = new ObjectTarget("bucket", "key");
  private static final byte[] CSV = "csv".getBytes(StandardCharsets.UTF_8);
  private static final S3Exception CONFLICT =
      (S3Exception) S3Exception.builder().statusCode(412).build();

  private S3Client s3;
  private ReleaseStore releaseStore;
  private ExistingObjectPolicy reuse;

  @BeforeEach
  void setUp() {
    s3 = mock(S3Client.class);
    releaseStore = mock(ReleaseStore.class);
    reuse = ExistingObjectPolicy.reuseIfIdentical(s3, releaseStore);
    when(s3.abortMultipartUpload(any(Consumer.class)))
        .thenReturn(AbortMultipartUploadResponse.builder().build());
  }

  @Test
  void failOnConflictRethrowsObjectConflict() {
    var policy = ExistingObjectPolicy.failOnConflict();

    var thrown =
        assertThrows(
            S3Exception.class, () -> policy.resolveObjectConflict(TARGET, "upload", "sha", CONFLICT));

    assertSame(CONFLICT, thrown);
  }

  @Test
  void failOnConflictRethrowsEvidenceConflict() {
    var policy = ExistingObjectPolicy.failOnConflict();

    var thrown =
        assertThrows(
            S3Exception.class,
            () -> policy.resolveEvidenceConflict(TARGET, "evidence-key", "{}", CONFLICT));

    assertSame(CONFLICT, thrown);
  }

  @Test
  void reuseReturnsExistingVersionWhenContentIsIdentical() throws Exception {
    stubExisting("existing-version", CSV);

    String version = reuse.resolveObjectConflict(TARGET, "upload", sha256(CSV), CONFLICT);

    assertEquals("existing-version", version);
    verify(s3).abortMultipartUpload(any(Consumer.class));
  }

  @Test
  void reuseRejectsExistingObjectWithDifferentContent() {
    stubExisting("existing-version", "other".getBytes(StandardCharsets.UTF_8));
    var sha = sha256(CSV);

    var failure =
        assertThrows(
            IllegalArgumentException.class,
            () -> reuse.resolveObjectConflict(TARGET, "upload", sha, CONFLICT));

    assertEquals("Existing extracted CSV differs from the source ZIP", failure.getMessage());
  }

  @Test
  void reuseRejectsExistingObjectWithoutVersioning() {
    when(s3.headObject(any(HeadObjectRequest.class)))
        .thenReturn(HeadObjectResponse.builder().versionId("null").build());
    var sha = sha256(CSV);

    var failure =
        assertThrows(
            IllegalArgumentException.class,
            () -> reuse.resolveObjectConflict(TARGET, "upload", sha, CONFLICT));

    assertEquals("S3 versioning must be enabled", failure.getMessage());
  }

  @Test
  void reuseAcceptsIdenticalEvidence() throws Exception {
    var recorded = new ObjectMapper().readTree("{\"bytes\":3}");
    when(releaseStore.read("bucket", "evidence-key")).thenReturn(recorded);

    assertDoesNotThrow(
        () -> reuse.resolveEvidenceConflict(TARGET, "evidence-key", "{\"bytes\":3}", CONFLICT));
  }

  @Test
  void reuseRejectsDifferingEvidence() throws Exception {
    when(releaseStore.read("bucket", "evidence-key"))
        .thenReturn(new ObjectMapper().readTree("{\"bytes\":4}"));

    var failure =
        assertThrows(
            IllegalArgumentException.class,
            () ->
                reuse.resolveEvidenceConflict(
                    TARGET, "evidence-key", "{\"bytes\":3}", CONFLICT));

    assertEquals("Existing checksum evidence differs from the extracted CSV", failure.getMessage());
  }

  @Test
  void reuseRejectsMissingEvidence() throws Exception {
    when(releaseStore.read("bucket", "evidence-key")).thenReturn(null);

    assertThrows(
        IllegalArgumentException.class,
        () -> reuse.resolveEvidenceConflict(TARGET, "evidence-key", "{}", CONFLICT));
  }

  @Test
  void checksumVerifierAcceptsMatchingMd5IgnoringCase() {
    assertDoesNotThrow(() -> ChecksumVerifier.md5("ABCDEF").verify("abcdef"));
  }

  @Test
  void checksumVerifierRejectsDifferentMd5() {
    var failure =
        assertThrows(
            StreamingS3.ChecksumMismatchException.class,
            () -> ChecksumVerifier.md5("expected").verify("actual"));

    assertEquals("OS MD5 checksum mismatch: expected=expected actual=actual", failure.getMessage());
  }

  @Test
  void checksumVerifierNoneAcceptsAnything() {
    assertDoesNotThrow(() -> ChecksumVerifier.none().verify("anything"));
  }

  @Test
  void objectTargetExposesLogFields() {
    assertEquals(Map.of("bucket", "bucket", "key", "key"), TARGET.logFields());
  }

  private void stubExisting(String version, byte[] content) {
    when(s3.headObject(any(HeadObjectRequest.class)))
        .thenReturn(HeadObjectResponse.builder().versionId(version).build());
    when(s3.getObject(any(GetObjectRequest.class)))
        .thenReturn(
            new ResponseInputStream<>(
                GetObjectResponse.builder().build(),
                AbortableInputStream.create(new ByteArrayInputStream(content))));
  }

  private static String sha256(byte[] bytes) {
    try {
      return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(bytes));
    } catch (Exception e) {
      throw new IllegalStateException(e);
    }
  }
}
