package uk.gov.companieshouse.addresslookup.lambda.shared.storage;

/** Strategy that proves the streamed bytes match a supplier checksum before the upload completes. */
@FunctionalInterface
interface ChecksumVerifier {

  void verify(String actualMd5) throws StreamingS3.ChecksumMismatchException;

  static ChecksumVerifier none() {
    return actualMd5 -> {};
  }

  static ChecksumVerifier md5(String expectedMd5) {
    return actualMd5 -> {
      if (!expectedMd5.equalsIgnoreCase(actualMd5)) {
        throw new StreamingS3.ChecksumMismatchException(expectedMd5, actualMd5);
      }
    };
  }
}
