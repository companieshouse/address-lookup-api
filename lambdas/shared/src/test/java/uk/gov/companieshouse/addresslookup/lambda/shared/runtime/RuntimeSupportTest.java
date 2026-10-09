package uk.gov.companieshouse.addresslookup.lambda.shared.runtime;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;

final class RuntimeSupportTest {
  @Test
  void sanitizesUrlQueryStringForLogs() {
    assertEquals(
        "https://api.os.uk/downloads/v1/dataPackages/12345/versions/159159/downloads?***",
        RuntimeSupport.sanitizeUrl(
            "https://api.os.uk/downloads/v1/dataPackages/12345/versions/159159/downloads?fileName=add_isl_royalmailaddress.zip&token=secret"));
  }
}
