package uk.gov.companieshouse.addresslookup.lambda.extraction.service;

import java.io.EOFException;
import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.channels.ClosedChannelException;
import java.nio.channels.NonWritableChannelException;
import java.nio.channels.SeekableByteChannel;
import java.util.zip.ZipException;
import software.amazon.awssdk.services.s3.S3Client;
import software.amazon.awssdk.services.s3.model.GetObjectRequest;
import software.amazon.awssdk.services.s3.model.HeadObjectRequest;

final class S3ZipChannel implements SeekableByteChannel {
  private static final int BUFFER_BYTES = 4 * 1024 * 1024;

  private final S3Client s3;
  private final String bucket;
  private final String key;
  private final String version;
  private final long size;
  private boolean open = true;
  private long position;
  private long readAheadEnd = -1;
  private long bufferStart = -1;
  private byte[] buffer = new byte[0];

  S3ZipChannel(S3Client s3, String bucket, String key, String version) throws ZipException {
    this.s3 = s3;
    this.bucket = bucket;
    this.key = key;
    this.version = version;
    var head = s3.headObject(HeadObjectRequest.builder()
        .bucket(bucket).key(key).versionId(version).build());
    this.size = head.contentLength();
    if (!version.equals(head.versionId()))
      throw new IllegalStateException("Pinned FULL ZIP version changed: " + key);
    if (size <= 0) throw new ZipException("Empty FULL ZIP: " + key);
  }

  void readAheadThrough(long end) {
    if (end < 0 || end > size) throw new IllegalArgumentException("Invalid ZIP entry range");
    readAheadEnd = end;
    bufferStart = -1;
    buffer = new byte[0];
  }

  void stopReadAhead() {
    readAheadEnd = -1;
    bufferStart = -1;
    buffer = new byte[0];
  }

  @Override
  public int read(ByteBuffer dst) throws IOException {
    ensureOpen();
    if (!dst.hasRemaining()) return 0;
    if (position >= size) return -1;
    int total = 0;
    while (dst.hasRemaining() && position < size) {
      if (bufferStart < 0 || position < bufferStart || position >= bufferStart + buffer.length) {
        int length = (int) Math.min(size - position,
            readAheadEnd < 0 ? dst.remaining() : Math.min(BUFFER_BYTES, readAheadEnd - position));
        if (length <= 0) throw new EOFException("ZIP member read exceeded compressed size");
        long end = position + length - 1;
        var request = GetObjectRequest.builder().bucket(bucket).key(key).versionId(version)
            .range("bytes=" + position + "-" + end).build();
        try (var input = s3.getObject(request)) {
          String expectedRange = "bytes " + position + "-" + end + "/" + size;
          if (!expectedRange.equals(input.response().contentRange())
              || !version.equals(input.response().versionId())) {
            throw new IOException("S3 did not return the pinned ZIP byte range");
          }
          buffer = input.readNBytes(length);
        }
        if (buffer.length != length) throw new EOFException("Truncated S3 ZIP byte range");
        bufferStart = position;
      }
      int offset = (int) (position - bufferStart);
      int count = Math.min(dst.remaining(), buffer.length - offset);
      dst.put(buffer, offset, count);
      position += count;
      total += count;
    }
    return total;
  }

  @Override
  public int write(ByteBuffer src) {
    throw new NonWritableChannelException();
  }

  @Override
  public long position() throws IOException {
    ensureOpen();
    return position;
  }

  @Override
  public SeekableByteChannel position(long newPosition) throws IOException {
    ensureOpen();
    if (newPosition < 0) throw new IllegalArgumentException("Negative ZIP position");
    position = newPosition;
    return this;
  }

  @Override
  public long size() throws IOException {
    ensureOpen();
    return size;
  }

  @Override
  public SeekableByteChannel truncate(long newSize) {
    throw new NonWritableChannelException();
  }

  @Override
  public boolean isOpen() {
    return open;
  }

  @Override
  public void close() {
    open = false;
    buffer = new byte[0];
  }

  private void ensureOpen() throws ClosedChannelException {
    if (!open) throw new ClosedChannelException();
  }
}
