package uk.gov.companieshouse.addresslookup.lambda.shared.runtime;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import java.lang.reflect.Constructor;
import java.nio.charset.StandardCharsets;
import org.junit.jupiter.api.Test;

final class RuntimeSupportTest {
  @Test
  void sanitizesUrlQueryStringForLogs() {
    assertEquals(
        "https://api.os.uk/downloads/v1/dataPackages/12345/versions/159159/downloads?***",
        RuntimeSupport.sanitizeUrl(
            "https://api.os.uk/downloads/v1/dataPackages/12345/versions/159159/downloads?fileName=add_isl_royalmailaddress.zip&token=secret"));
  }

  @Test
  void computesSha256() {
    assertEquals(
        "ba7816bf8f01cfea414140de5dae2223b00361a396177a9cb410ff61f20015ad",
        RuntimeSupport.sha("abc".getBytes(StandardCharsets.UTF_8)));
  }

  @Test
  void unknownDigestAlgorithmIsReported() {
    var ex =
        assertThrows(IllegalStateException.class, () -> RuntimeSupport.digest("NOPE", new byte[0]));

    assertEquals("Digest algorithm is not available: NOPE", ex.getMessage());
  }

  @Test
  void utilityClassCannotBeInstantiatedExternally() throws ReflectiveOperationException {
    Constructor<RuntimeSupport> constructor = RuntimeSupport.class.getDeclaredConstructor();
    constructor.setAccessible(true);

    assertEquals(RuntimeSupport.class, constructor.newInstance().getClass());
  }
}
