package uk.gov.companieshouse.addresslookup.lambda.extraction.service;

import static uk.gov.companieshouse.addresslookup.lambda.shared.runtime.RuntimeSupport.check;
import static uk.gov.companieshouse.addresslookup.lambda.shared.runtime.RuntimeSupport.required;
import static uk.gov.companieshouse.release.model.DatasetCatalog.extractionHeader;

import com.amazonaws.services.lambda.runtime.Context;
import java.io.ByteArrayOutputStream;
import java.io.FilterInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.PushbackInputStream;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.charset.StandardCharsets;
import java.security.DigestInputStream;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.Collections;
import java.util.Comparator;
import java.util.Arrays;
import java.util.HashSet;
import java.util.HexFormat;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.TimeUnit;
import java.util.zip.CRC32;
import java.util.zip.ZipEntry;
import java.util.zip.ZipException;
import org.apache.commons.compress.archivers.zip.ZipArchiveEntry;
import org.apache.commons.compress.archivers.zip.ZipFile;
import org.apache.commons.csv.CSVFormat;
import org.apache.commons.csv.CSVParser;
import org.jspecify.annotations.NonNull;
import org.springframework.stereotype.Component;
import software.amazon.awssdk.services.s3.S3Client;
import software.amazon.awssdk.services.s3.model.S3Exception;
import uk.gov.companieshouse.addresslookup.lambda.shared.acquisition.AcquisitionProperties;
import uk.gov.companieshouse.addresslookup.lambda.shared.acquisition.ExtractionProperties;
import uk.gov.companieshouse.addresslookup.lambda.shared.storage.ReleaseStore;
import uk.gov.companieshouse.addresslookup.lambda.shared.storage.StreamingS3;
import uk.gov.companieshouse.logging.Logger;
import uk.gov.companieshouse.logging.LoggerFactory;

@Component
public final class ExtractionComponent {
  private static final Logger LOG = LoggerFactory.getLogger(ExtractionComponent.class.getName());
  private static final int MAX_CSV_HEADER_BYTES = 64 * 1024;
  private static final int DRAIN_BUFFER_BYTES = 1024 * 1024;
  private static final long PROGRESS_INTERVAL_BYTES = 256L * 1024 * 1024;

  private enum EntryAction {
    LOAD,
    EXTRACT_ONLY,
    SKIP
  }

  private final S3Client s3;
  private final AcquisitionProperties acquisitionProperties;
  private final ExtractionProperties extractionProperties;

  static class CsvHeaderException extends IOException {
    CsvHeaderException(String msg) {
      super(msg);
    }
  }

  static class ArchiveValidationException extends IOException {
    ArchiveValidationException(String msg) {
      super(msg);
    }
  }

  static class MissingPrimaryCsvException extends IOException {
    MissingPrimaryCsvException(String msg) {
      super(msg);
    }
  }

  record Member(String action, long bytes, String outputKey, String versionId, String sha256) {}

  interface MemberCheckpoint {
    Member completed(String name);
    boolean recovering(String name);
    void markRecovering(String name, Member member) throws IOException;
    void save(String name, Member member) throws IOException;
    Set<String> names();
  }

  public ExtractionComponent(S3Client s3, AcquisitionProperties acquisitionProperties,
                             ExtractionProperties extractionProperties) {
    this.s3 = s3;
    this.acquisitionProperties = acquisitionProperties;
    this.extractionProperties = extractionProperties;
    AcquisitionProperties.required(acquisitionProperties.controlBucket(), "CONTROL_BUCKET");
  }

  public ExtractionResult extract(Map<String, Object> event, Context context)
      throws IOException, NoSuchAlgorithmException,
          StreamingS3.ChecksumMismatchException,
          StreamingS3.ExistingObjectVerificationException {
    return new FullVersionExtraction(s3, acquisitionProperties, extractionProperties, this)
        .extract(FullExtractionRequest.parse(event), context);
  }

  void extractFullZip(String sourceKey, String sourceVersion, String dataset,
                      String prefix, String bucket,
                      Context context, List<String> outputs, List<String> skipped,
                      MemberCheckpoint checkpoint)
      throws IOException, NoSuchAlgorithmException,
          StreamingS3.ChecksumMismatchException,
          StreamingS3.ExistingObjectVerificationException {
    String expectedHeader = extractionHeader(dataset);
    var feed = extractionProperties == null ? null : extractionProperties.full();
    check(feed != null && feed.datasetPolicies() != null, "Missing FULL extraction policies");
    var policies = feed.datasetPolicies().stream()
        .filter(policy -> dataset.equals(policy.dataset())).toList();
    check(policies.size() == 1, "Expected one FULL extraction policy for " + dataset);
    var policy = policies.getFirst();
    long max = policy.maxExtractedBytes();
    var store = new ReleaseStore(s3);
    try (var channel = new S3ZipChannel(s3, bucket, sourceKey, sourceVersion);
         var zip = openZip(channel, policy.maxEntries())) {
      var entries = Collections.list(zip.getEntries());
      check(entries.size() <= policy.maxEntries(),
          () -> new ArchiveValidationException("Too many ZIP entries"));
      entries.sort(Comparator.comparingLong(ZipArchiveEntry::getLocalHeaderOffset));
      var names = new HashSet<String>();
      boolean primary = false;
      long archiveExpanded = 0;
      long archiveCompressed = 0;
      long previousEnd = 0;
      for (ZipArchiveEntry entry : entries) {
        EntryAction action = validateEntry(entry, dataset, names, policy);
        validateLocalHeader(channel, entry);
        check(entry.getDiskNumberStart() == 0 && !entry.getGeneralPurposeBit().usesEncryption()
                && (entry.getMethod() == ZipEntry.STORED || entry.getMethod() == ZipEntry.DEFLATED)
                && zip.canReadEntryData(entry),
            () -> new ArchiveValidationException("Unsupported ZIP member: " + entry.getName()));
        long expanded = entry.getSize();
        long compressed = entry.getCompressedSize();
        long start = entry.getDataOffset();
        check(expanded >= 0 && compressed >= 0 && entry.getCrc() >= 0
                && start >= previousEnd && start <= channel.size()
                && compressed <= channel.size() - start
                && (entry.getMethod() != ZipEntry.STORED || expanded == compressed),
            () -> new ZipException("Invalid ZIP member metadata: " + entry.getName()));
        previousEnd = start + compressed;
        check(expanded <= max - archiveExpanded,
            () -> new ZipException("Expanded archive exceeds maximum allowed size"));
        archiveExpanded += expanded;
        check(compressed <= channel.size() - archiveCompressed,
            () -> new ZipException("Invalid ZIP archive compressed size"));
        archiveCompressed += compressed;
        check(!exceedsRatio(expanded, compressed, policy.maxEntryCompressionRatio()),
            () -> new ZipException("ZIP entry exceeds maximum compression ratio"));
        check(!exceedsRatio(archiveExpanded, archiveCompressed,
                policy.maxArchiveCompressionRatio()),
            () -> new ZipException("ZIP archive exceeds maximum compression ratio"));
      }
      for (ZipArchiveEntry entry : entries) {
        EntryAction action = actionFor(entry, dataset, policy);
        String name = entry.getName();
        String outputKey = prefix + name;
        Member previous = checkpoint.completed(name);
        boolean recovering = checkpoint.recovering(name);
        boolean previouslyRecovering = recovering;
        boolean checkpointed = previous != null && !recovering;
        if (previous == null && action != EntryAction.SKIP) {
          previous = previousOutput(store, bucket, outputKey, action);
        }
        if (previous != null) {
          check(previous.action().equals(action.name())
                  && previous.bytes() >= 0
                  && (action == EntryAction.SKIP
                      ? previous.outputKey() == null && previous.versionId() == null
                          && previous.sha256() == null
                      : outputKey.equals(previous.outputKey())
                          && previous.versionId() != null
                          && previous.sha256() != null
                          && previous.sha256().matches("[0-9a-f]{64}")),
              "Invalid FULL member checkpoint: " + name);
          if (action != EntryAction.SKIP) {
            String currentVersion;
            try {
              currentVersion = store.version(bucket, outputKey);
            } catch (S3Exception failure) {
              if (failure.statusCode() != 404) throw failure;
              currentVersion = null;
            }
            if (currentVersion == null && !recovering) {
              LOG.error("FULL ZIP member output missing; restarting extraction",
                  Map.of("bucket", bucket, "key", outputKey, "member", name));
              checkpoint.markRecovering(name, previous);
              recovering = true;
              checkpointed = false;
            } else if (currentVersion != null && !currentVersion.equals(previous.versionId())) {
              check(recovering, "Completed ZIP member output changed: " + outputKey);
              Member current = previousOutput(store, bucket, outputKey, action);
              if (current != null && currentVersion.equals(current.versionId())) {
                check(previous.bytes() == current.bytes()
                    && previous.sha256().equals(current.sha256()),
                    "Recovered ZIP member output changed: " + outputKey);
                previous = current;
                recovering = false;
              }
            }
          }
        }
        StreamingS3.Uploaded uploaded = null;
        long actualBytes = entry.getSize();
        if (action != EntryAction.SKIP) {
          channel.readAheadThrough(entry.getDataOffset() + entry.getCompressedSize());
          try (var limited = new VerifiedEntryInputStream(zip.getInputStream(entry), entry, max)) {
            var content = new PushbackInputStream(limited, MAX_CSV_HEADER_BYTES);
            if (action == EntryAction.LOAD) {
              byte[] header = readHeader(content);
              validateHeaders(header, expectedHeader);
              content.unread(header);
              primary = true;
            }
            if (recovering) {
              Member expected = previous;
              uploaded = new StreamingS3(s3, context::getRemainingTimeInMillis)
                  .putRecoverMissingWithProgress(content, bucket, outputKey, max,
                      expected.versionId(), expected.sha256(), expected.bytes(),
                      previouslyRecovering,
                      progress -> logUploadProgress(name, "RECOVER", limited, context, progress));
            } else if (previous != null) {
              var digest = MessageDigest.getInstance("SHA-256");
              drainReplay(new DigestInputStream(content, digest), limited, name, context);
              check(previous.sha256().equals(HexFormat.of().formatHex(digest.digest())),
                  "Completed ZIP member content changed: " + name);
              check(previous.bytes() == limited.entryExpanded,
                  "Completed ZIP member size changed: " + name);
            } else {
              uploaded = new StreamingS3(s3, context::getRemainingTimeInMillis)
                  .putIdempotentWithProgress(content, bucket, outputKey, max, progress ->
                      logUploadProgress(name, action.name(), limited, context, progress));
            }
            actualBytes = limited.entryExpanded;
          } finally {
            channel.stopReadAhead();
          }
        } else if (previous != null) {
          check(previous.bytes() == actualBytes,
              "Completed ZIP member size changed: " + name);
        }
        if (!checkpointed) {
          Member completed = uploaded == null ? previous
              : new Member(action.name(), actualBytes,
                  outputKey, uploaded.version(), uploaded.sha256());
          if (completed == null) {
            completed = new Member(action.name(), actualBytes, null, null, null);
          }
          checkpoint.save(name, completed);
        }
        if (action == EntryAction.SKIP) skipped.add(name);
        else outputs.add(outputKey);
        LOG.info("FULL ZIP member complete", Map.of(
            "member", name, "action", action.name(),
            "expanded_bytes", actualBytes,
            "compressed_bytes", entry.getCompressedSize(),
            "remaining_ms", context.getRemainingTimeInMillis()));
      }
      check(primary, () -> new MissingPrimaryCsvException("CSV_NOT_FOUND"));
      check(names.equals(checkpoint.names()), "FULL ZIP member checkpoint does not match archive");
    }
  }

  private static void validateIndexEntryCount(S3ZipChannel channel, int limit)
      throws IOException {
    int maxTail = (int) Math.min(channel.size(), 65_535L + 22);
    if (maxTail < 22) throw new ZipException("Missing ZIP central directory");
    for (int tailLength = 22; ; tailLength = Math.min(maxTail, tailLength * 2)) {
      byte[] tail = new byte[tailLength];
      channel.position(channel.size() - tailLength);
      readFully(channel, tail);
      var footer = ByteBuffer.wrap(tail).order(ByteOrder.LITTLE_ENDIAN);
      for (int i = tailLength - 22; i >= 0; i--) {
        if (footer.getInt(i) != 0x06054b50
            || i + 22 + Short.toUnsignedInt(footer.getShort(i + 20)) != tailLength) continue;
        check(footer.getInt(i + 4) == 0
                && Short.toUnsignedInt(footer.getShort(i + 8))
                    == Short.toUnsignedInt(footer.getShort(i + 10)),
            () -> new ZipException("Unsupported multi-disk ZIP"));
        int count = Short.toUnsignedInt(footer.getShort(i + 10));
        check(count <= limit || count == 0xffff,
            () -> new ArchiveValidationException("Too many ZIP entries"));
        long locatorAt = channel.size() - tailLength + i - 20;
        if (locatorAt >= 0) {
          byte[] locatorBytes = new byte[20];
          channel.position(locatorAt);
          readFully(channel, locatorBytes);
          var locator = ByteBuffer.wrap(locatorBytes).order(ByteOrder.LITTLE_ENDIAN);
          if (locator.getInt(0) == 0x07064b50) {
            check(locator.getInt(4) == 0 && locator.getInt(16) == 1,
                () -> new ZipException("Invalid ZIP64 locator"));
            long zip64At = locator.getLong(8);
            check(zip64At >= 0 && zip64At <= channel.size() - 56,
                () -> new ZipException("Invalid ZIP64 index offset"));
            byte[] zip64Bytes = new byte[56];
            channel.position(zip64At);
            readFully(channel, zip64Bytes);
            var zip64 = ByteBuffer.wrap(zip64Bytes).order(ByteOrder.LITTLE_ENDIAN);
            check(zip64.getInt(0) == 0x06064b50 && zip64.getLong(4) >= 44
                    && zip64.getInt(16) == 0 && zip64.getInt(20) == 0
                    && zip64.getLong(24) == zip64.getLong(32)
                    && (count == 0xffff || count == zip64.getLong(32)),
                () -> new ZipException("Invalid ZIP64 index"));
            check(zip64.getLong(32) >= 0 && zip64.getLong(32) <= limit,
                () -> new ArchiveValidationException("Too many ZIP entries"));
            return;
          }
        }
        check(count != 0xffff, () -> new ZipException("Missing ZIP64 locator"));
        return;
      }
      if (tailLength == maxTail) break;
    }
    throw new ZipException("Missing ZIP central directory");
  }

  private static ZipFile openZip(S3ZipChannel channel, int maxEntries) throws IOException {
    validateIndexEntryCount(channel, maxEntries);
    try {
      return ZipFile.builder().setSeekableByteChannel(channel).setMaxNumberOfDisks(1).get();
    } catch (IOException failure) {
      if (failure.getCause() instanceof ZipException invalid) throw invalid;
      throw failure;
    }
  }

  private static void logUploadProgress(String name, String action,
                                        VerifiedEntryInputStream limited, Context context,
                                        StreamingS3.PartProgress progress) {
    LOG.info("FULL ZIP member progress", Map.of(
        "member", name, "action", action,
        "part", progress.partNumber(),
        "expanded_bytes", progress.bytes(),
        "compressed_bytes", limited.entryCompressed,
        "read_inflate_ms", millis(progress.readNanos()),
        "digest_ms", millis(progress.digestNanos()),
        "upload_ms", millis(progress.uploadNanos()),
        "elapsed_ms", millis(progress.elapsedNanos()),
        "remaining_ms", context.getRemainingTimeInMillis()));
  }

  private static Member previousOutput(ReleaseStore store, String bucket, String outputKey,
                                       EntryAction action) throws IOException {
    var evidence = store.read(bucket, StreamingS3.checksumKey(outputKey));
    if (evidence == null) return null;
    check(bucket.equals(required(evidence, "bucket"))
            && outputKey.equals(required(evidence, "key"))
            && evidence.path("bytes").isIntegralNumber()
            && evidence.path("bytes").longValue() > 0,
        "Invalid existing output evidence: " + outputKey);
    return new Member(action.name(), evidence.path("bytes").longValue(), outputKey,
        required(evidence, "versionId"), required(evidence, "sha256"));
  }

  private static void drainReplay(InputStream content, VerifiedEntryInputStream limited,
                                  String name, Context context)
      throws IOException {
    byte[] buffer = new byte[DRAIN_BUFFER_BYTES];
    long started = System.nanoTime();
    long nextProgress = PROGRESS_INTERVAL_BYTES;
    while (content.read(buffer) != -1) {
      if (limited.entryExpanded >= nextProgress) {
        LOG.info("FULL ZIP member progress", Map.of(
            "member", name, "action", "REPLAY",
            "expanded_bytes", limited.entryExpanded,
            "compressed_bytes", limited.entryCompressed,
            "read_inflate_ms", millis(System.nanoTime() - started),
            "upload_ms", 0,
            "remaining_ms", context.getRemainingTimeInMillis()));
        nextProgress = limited.entryExpanded + PROGRESS_INTERVAL_BYTES;
      }
    }
  }

  private static long millis(long nanos) {
    return TimeUnit.NANOSECONDS.toMillis(nanos);
  }

  private static EntryAction validateEntry(ZipEntry entry, String dataset, Set<String> names,
                                           ExtractionProperties.DatasetPolicy policy)
      throws ArchiveValidationException {
    String name = entry.getName();
    check(names.size() < policy.maxEntries() && names.add(name),
        () -> new ArchiveValidationException("Too many or duplicate ZIP entries"));
    check(!entry.isDirectory() && !name.contains("/") && !name.contains("\\") && !name.contains(".."),
        () -> new ArchiveValidationException("Unexpected ZIP member path"));
    if (name.equals(dataset + ".csv")) return EntryAction.LOAD;
    for (String suffix : policy.extractOnly()) {
      if (name.equals(dataset + suffix + ".csv")) return EntryAction.EXTRACT_ONLY;
    }

    for (String suffix : policy.skip()) {
      if (name.equals(dataset + suffix + ".csv")) return EntryAction.SKIP;
    }
    throw new ArchiveValidationException("Unexpected ZIP member: " + name);
  }

  private static EntryAction actionFor(ZipEntry entry, String dataset,
                                       ExtractionProperties.DatasetPolicy policy) {
    if (entry.getName().equals(dataset + ".csv")) return EntryAction.LOAD;
    for (String suffix : policy.extractOnly()) {
      if (entry.getName().equals(dataset + suffix + ".csv")) return EntryAction.EXTRACT_ONLY;
    }
    return EntryAction.SKIP;
  }

  private static void validateLocalHeader(S3ZipChannel channel, ZipArchiveEntry entry)
      throws IOException {
    long offset = entry.getLocalHeaderOffset();
    check(offset >= 0 && offset <= channel.size() - 30,
        () -> new ZipException("Invalid ZIP local header offset: " + entry.getName()));
    byte[] header = new byte[30];
    channel.position(offset);
    readFully(channel, header);
    ByteBuffer fields = ByteBuffer.wrap(header).order(ByteOrder.LITTLE_ENDIAN);
    int nameLength = Short.toUnsignedInt(fields.getShort(26));
    int extraLength = Short.toUnsignedInt(fields.getShort(28));
    byte[] name = entry.getRawName();
    check(fields.getInt(0) == 0x04034b50
            && Short.toUnsignedInt(fields.getShort(6)) == entry.getRawFlag()
            && Short.toUnsignedInt(fields.getShort(8)) == entry.getMethod()
            && nameLength == name.length
            && offset + 30L + nameLength + extraLength == entry.getDataOffset()
            && entry.getDataOffset() <= channel.size(),
        () -> new ZipException("ZIP local header differs from index: " + entry.getName()));
    byte[] localName = new byte[nameLength];
    readFully(channel, localName);
    check(Arrays.equals(name, localName),
        () -> new ZipException("ZIP member name differs from index: " + entry.getName()));
  }

  private static void readFully(S3ZipChannel channel, byte[] bytes) throws IOException {
    ByteBuffer buffer = ByteBuffer.wrap(bytes);
    while (buffer.hasRemaining()) {
      if (channel.read(buffer) < 0) throw new ZipException("Truncated ZIP local header");
    }
  }

  private static void validateHeaders(byte[] header, String expectedHeader)
      throws IOException, CsvHeaderException {
    try (CSVParser parser =
        CSVParser.parse(new String(header, StandardCharsets.UTF_8),
            CSVFormat.DEFAULT.builder().setHeader().setSkipHeaderRecord(true)
                .setIgnoreEmptyLines(false).setTrim(false).get())) {
      List<String> headers = parser.getHeaderNames();
      List<String> expectedFields = parseExpectedHeader(expectedHeader);
      check(headers.size() == expectedFields.size(),
          () -> new CsvHeaderException("Header size mismatch"));
      check(headers.equals(expectedFields), () -> new CsvHeaderException("Header mismatch"));
    }
  }

  private static byte[] readHeader(InputStream input) throws IOException {
    var header = new ByteArrayOutputStream();
    int value;
    while ((value = input.read()) != -1) {
      header.write(value);
      if (header.size() > MAX_CSV_HEADER_BYTES) {
        throw new ZipException("CSV header exceeds maximum allowed size");
      }
      if (value == '\n') break;
    }
    return header.toByteArray();
  }

  private static List<String> parseExpectedHeader(String expectedHeader)
      throws IOException, CsvHeaderException {
    try (CSVParser parser = CSVParser.parse(expectedHeader, CSVFormat.DEFAULT)) {
      var records = parser.getRecords();
      check(records.size() == 1,
          () -> new CsvHeaderException("Expected schema header must contain one CSV record"));
      return records.getFirst().toList();
    }
  }

  private static final class VerifiedEntryInputStream extends FilterInputStream {
    private final ZipArchiveEntry entry;
    private final long expandedLimit;
    private final CRC32 crc = new CRC32();
    private long entryExpanded;
    private long entryCompressed;

    private VerifiedEntryInputStream(InputStream input, ZipArchiveEntry entry, long max) {
      super(input);
      this.entry = entry;
      this.expandedLimit = max;
      this.entryCompressed = entry.getCompressedSize();
    }

    @Override
    public int read() throws IOException {
      int value = in.read();
      if (value == -1) finish();
      else {
        add(1);
        crc.update(value);
      }
      return value;
    }

    @Override
    public int read(byte @NonNull [] bytes, int offset, int length) throws IOException {
      int count = in.read(bytes, offset, length);
      if (count == -1) finish();
      else if (count > 0) {
        add(count);
        crc.update(bytes, offset, count);
      }
      return count;
    }

    private void add(int count) throws ZipException {
      entryExpanded += count;
      if (entryExpanded > expandedLimit || entryExpanded > entry.getSize())
        throw new ZipException("Expanded ZIP member exceeds declared size or limit");
    }

    private void finish() throws ZipException {
      if (entryExpanded != entry.getSize() || crc.getValue() != entry.getCrc())
        throw new ZipException("ZIP member size or CRC mismatch: " + entry.getName());
    }
  }

  private static boolean exceedsRatio(long expanded, long compressed, int maxRatio) {
    return expanded > 0 && (expanded - 1) / maxRatio >= Math.max(1, compressed);
  }
}
