package uk.gov.companieshouse.addresslookup.lambda.download;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertSame;

import org.junit.jupiter.api.Test;

class DownloadFailedExceptionTest {

  @Test
  void keepsCauseAndRetryMessage() {
    var cause = new IllegalArgumentException("failed to download CVS file.");

    var exception = new DownloadFailedException(cause);

    assertEquals("download failed; durable state allows retry", exception.getMessage());
    assertSame(cause, exception.getCause());
  }
}
