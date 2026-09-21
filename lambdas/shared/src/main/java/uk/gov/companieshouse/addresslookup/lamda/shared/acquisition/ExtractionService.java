package uk.gov.companieshouse.addresslookup.lamda.shared.acquisition;

import com.amazonaws.services.lambda.runtime.Context;
import com.fasterxml.jackson.databind.JsonNode;
import software.amazon.awssdk.services.s3.S3Client;
import uk.gov.companieshouse.addresslookup.lamda.shared.storage.ReleaseStore;
import uk.gov.companieshouse.addresslookup.lamda.shared.storage.StreamingS3;

import java.io.*;
import java.util.HashSet;
import java.util.Map;
import java.util.UUID;
import java.util.zip.ZipEntry;
import java.util.zip.ZipInputStream;

import static uk.gov.companieshouse.addresslookup.lamda.shared.acquisition.AcquisitionSupport.*;
import static uk.gov.companieshouse.addresslookup.lamda.shared.runtime.RuntimeSupport.*;

/** Red-zone extraction reads only the exact clean ZIP version. */
public final class ExtractionService {
  private final S3Client s3;
  private final AcquisitionProperties properties;

  public ExtractionService(S3Client s3, AcquisitionProperties properties) {
    this.s3 = s3;
    this.properties = properties;
    AcquisitionProperties.required(properties.scannedBucket(), "SCANNED_BUCKET");
  }

  public String unzip(Map<String, Object> event, Context context) throws Exception {
    JsonNode d = detail(event);
    var t = table(required(d, "dataset"));
    var store = new ReleaseStore(s3);
    String bucket = properties.sourceBucket(), checked = properties.scannedBucket();
    String planKey = required(d, "planKey");
    check(planKey.startsWith("acquisitions/"), "Invalid plan key");
    var plan = store.read(bucket, planKey);
    check(plan != null, "Missing acquisition plan");
    String id = releaseId(plan).toString();
    if (store.receipt(bucket, id, t.name(), "extract") != null) return "SKIPPED";
    var sourceRef = store.receipt(bucket, id, t.name(), "download");
    if (sourceRef == null || !store.clean(bucket, sourceRef)) return "WAITING_FOR_ZIP_SCAN";
    long max = properties.maxExtractedBytes(), total = 0;
    StreamingS3.Uploaded result = null;
    String key = "quarantine/csv/" + id + "/" + t.name() + "/" + UUID.randomUUID() + ".csv";
    try (var source =
            s3.getObject(
                b ->
                    b.bucket(bucket)
                        .key(required(sourceRef, "key"))
                        .versionId(required(sourceRef, "version")));
        var zip = new ZipInputStream(source)) {
      var names = new HashSet<String>();
      ZipEntry entry;
      while ((entry = zip.getNextEntry()) != null) {
        check(
            names.size() < 100 && names.add(entry.getName()), "Too many or duplicate ZIP entries");
        String name = entry.getName();
        check(
            !name.contains("/") && !name.contains("\\") && !name.contains(".."),
            "Unexpected ZIP member path");
        if (name.equals(t.name() + ".csv")) {
          check(result == null, "Duplicate primary CSV");
          var in = new PushbackInputStream(zip, 65536);
          var header = new ByteArrayOutputStream();
          int b;
          while ((b = in.read()) != -1) {
            header.write(b);
            check(header.size() < 65536, "Header too large");
            if (b == '\n') break;
          }
          check(
              header
                  .toString(java.nio.charset.StandardCharsets.UTF_8)
                  .stripTrailing()
                  .equals(t.header()),
              "CSV header does not match pinned OS DDL");
          in.unread(header.toByteArray());
          // Count all expanded bytes, including ignored ancillary datasets.
          final long budget = max - total;
          var bounded = new CountingInput(in, budget);
          result =
              new StreamingS3(s3, () -> context.getRemainingTimeInMillis())
                  .put(bounded, checked, key, null, budget);
          total += bounded.count;
        } else {
          check(name.startsWith(t.name() + "_") && name.endsWith(".csv"), "Unexpected ZIP member");
          byte[] buffer = new byte[65536];
          int n;
          while ((n = zip.read(buffer)) != -1) {
            total += n;
            check(total <= max, "ZIP expanded size limit");
            check(context.getRemainingTimeInMillis() > 30000, "Unzip deadline");
          }
        }
        zip.closeEntry();
      }
    }
    check(result != null, "Missing primary CSV");
    store.receipt(bucket, id, t.name(), "extract", result);
    return "CSV_SCAN";
  }

  static final class CountingInput extends FilterInputStream {
    long count;
    final long limit;

    CountingInput(InputStream in, long limit) {
      super(in);
      this.limit = limit;
    }

    public int read() throws IOException {
      int b = in.read();
      if (b >= 0) add(1);
      return b;
    }

    public int read(byte[] b, int off, int len) throws IOException {
      int n = in.read(b, off, len);
      if (n > 0) add(n);
      return n;
    }

    void add(int n) throws IOException {
      count += n;
      if (count > limit) throw new IOException("Expanded archive limit");
    }
  }
}
