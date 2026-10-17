package org.pipelineframework.csv.service;

import java.io.DataInputStream;
import java.io.DataOutputStream;
import java.io.IOException;
import java.io.Reader;
import java.nio.ByteBuffer;
import java.nio.channels.FileChannel;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Duration;
import java.util.Base64;
import java.util.HexFormat;
import java.util.Optional;
import java.util.concurrent.CancellationException;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.Flow;
import java.util.concurrent.CompletionStage;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.function.Function;

import com.opencsv.CSVParserBuilder;
import com.opencsv.CSVReader;
import com.opencsv.CSVReaderBuilder;
import org.pipelineframework.csv.common.domain.CsvPaymentsStableIdSupport;
import org.pipelineframework.csv.common.domain.FilePathAwareMappingStrategy;
import org.pipelineframework.csv.common.domain.PaymentRecord;
import org.pipelineframework.csv.common.mapper.CsvPaymentFileObjectMapper;
import org.pipelineframework.csv.domain.CsvPaymentsInputFile;
import org.pipelineframework.paging.PagedSourceCompletion;
import org.pipelineframework.paging.PagedSourceRequest;
import org.pipelineframework.paging.PagedSourceStream;
import org.pipelineframework.connector.ObjectReadSession;
import org.pipelineframework.repository.PayloadReference;

/** OpenCSV-backed logical-record cursor with exact UTF-8 byte checkpoints. */
final class OpenCsvPagedPaymentSource {
  private static final int CHECKPOINT_VERSION = 1;
  private static final int READ_CHUNK_BYTES = 8 * 1024;
  private static final long MAX_S3_CSV_BYTES = 1024L * 1024;
  private static final Duration OBJECT_READ_TIMEOUT = Duration.ofSeconds(20);
  private final Function<PayloadReference, CompletionStage<ObjectReadSession>> openRead;
  private final Duration readTimeout;

  OpenCsvPagedPaymentSource() {
    this(reference -> { throw new IllegalStateException("Object read binding is unavailable"); });
  }

  OpenCsvPagedPaymentSource(Function<PayloadReference, CompletionStage<ObjectReadSession>> openRead) {
    this(openRead, OBJECT_READ_TIMEOUT);
  }

  OpenCsvPagedPaymentSource(
      Function<PayloadReference, CompletionStage<ObjectReadSession>> openRead,
      Duration readTimeout) {
    this.openRead = java.util.Objects.requireNonNull(openRead);
    this.readTimeout = java.util.Objects.requireNonNull(readTimeout);
    if (readTimeout.isZero() || readTimeout.isNegative()) {
      throw new IllegalArgumentException("Object read timeout must be positive");
    }
  }

  PagedSourceStream<PaymentRecord> open(PagedSourceRequest<CsvPaymentsInputFile> request) {
    String snapshotIdentity = requireIdentity(request.input().sourceIdentity());
    SourceVersion version = sourceVersion(request.input(), snapshotIdentity);
    Cursor cursor = request.checkpoint()
        .map(token -> decode(token, request.sourceIdentity(), version))
        .orElseGet(() -> new Cursor(request.sourceIdentity(), version, headerOffset(request.input())));
    CompletableFuture<PagedSourceCompletion> completion = new CompletableFuture<>();
    return new PagedSourceStream<>(
        subscriber -> subscribe(subscriber, request, cursor, completion),
        completion);
  }

  private void subscribe(
      Flow.Subscriber<? super PaymentRecord> subscriber,
      PagedSourceRequest<CsvPaymentsInputFile> request,
      Cursor cursor,
      CompletableFuture<PagedSourceCompletion> completion) {
    try {
      FilePathAwareMappingStrategy<PaymentRecord> strategy = mappingStrategy(request.input().filepath());
      captureHeader(request.input(), strategy);
      CursorUtf8Reader source = openCursor(request.input(), cursor.offset());
      CSVReader reader = csvReader(source);
      subscriber.onSubscribe(new PageSubscription(
          subscriber, request, cursor, source, reader, strategy, completion));
    } catch (Throwable failure) {
      completion.completeExceptionally(failure);
      subscriber.onSubscribe(new EmptySubscription());
      subscriber.onError(failure);
    }
  }

  private static final class PageSubscription implements Flow.Subscription {
    private final Flow.Subscriber<? super PaymentRecord> downstream;
    private final PagedSourceRequest<CsvPaymentsInputFile> request;
    private final Cursor start;
    private final CursorUtf8Reader source;
    private final CSVReader reader;
    private final FilePathAwareMappingStrategy<PaymentRecord> strategy;
    private final CompletableFuture<PagedSourceCompletion> completion;
    private long demand;
    private int consumed;
    private boolean draining;
    private boolean terminated;

    private PageSubscription(
        Flow.Subscriber<? super PaymentRecord> downstream,
        PagedSourceRequest<CsvPaymentsInputFile> request,
        Cursor start,
        CursorUtf8Reader source,
        CSVReader reader,
        FilePathAwareMappingStrategy<PaymentRecord> strategy,
        CompletableFuture<PagedSourceCompletion> completion) {
      this.downstream = downstream;
      this.request = request;
      this.start = start;
      this.source = source;
      this.reader = reader;
      this.strategy = strategy;
      this.completion = completion;
    }

    @Override
    public synchronized void request(long requested) {
      if (terminated) {
        return;
      }
      if (requested <= 0) {
        fail(new IllegalArgumentException("Reactive Streams demand must be positive"));
        return;
      }
      demand = demand > Long.MAX_VALUE - requested ? Long.MAX_VALUE : demand + requested;
      if (draining) {
        return;
      }
      draining = true;
      try {
        drain();
      } finally {
        draining = false;
      }
    }

    private void drain() {
      while (!terminated && demand > 0) {
        if (consumed == request.maxRecords()) {
          long boundary;
          try {
            boundary = source.recordBoundaryPosition();
          } catch (Throwable failure) {
            fail(failure);
            return;
          }
          finish(boundary >= start.version().sizeBytes(), boundary);
          return;
        }
        String[] row;
        try {
          row = reader.readNext();
        } catch (Throwable failure) {
          fail(failure);
          return;
        }
        if (row == null) {
          finish(true, source.position());
          return;
        }
        long boundary;
        try {
          boundary = source.recordBoundaryPosition();
        } catch (Throwable failure) {
          fail(failure);
          return;
        }
        consumed++;
        if (isEmpty(row)) {
          if (consumed == request.maxRecords()) {
            finish(boundary >= start.version().sizeBytes(), boundary);
          }
          continue;
        }
        PaymentRecord item;
        try {
          item = strategy.populateNewBean(row);
          item.setId(CsvPaymentsStableIdSupport.paymentRecordId(
              item.getCsvPaymentsInputFilePath(), item.getCsvId(), item.getRecipient(),
              item.getAmount(), item.getCurrency()));
        } catch (Throwable failure) {
          fail(failure);
          return;
        }
        demand--;
        downstream.onNext(item);
        if (!terminated && consumed == request.maxRecords()) {
          finish(boundary >= start.version().sizeBytes(), boundary);
          return;
        }
      }
    }

    @Override
    public synchronized void cancel() {
      if (terminated) {
        return;
      }
      terminated = true;
      closeReader();
      completion.completeExceptionally(new CancellationException("paged CSV source cancelled"));
    }

    private void finish(boolean exhausted, long nextOffset) {
      terminated = true;
      try {
        reader.close();
        downstream.onComplete();
        completion.complete(new PagedSourceCompletion(
            consumed,
            exhausted ? Optional.empty() : Optional.of(encode(
                new Cursor(request.sourceIdentity(), start.version(), nextOffset))),
            exhausted));
      } catch (Throwable failure) {
        completion.completeExceptionally(failure);
        downstream.onError(failure);
      }
    }

    private void fail(Throwable failure) {
      terminated = true;
      closeReader();
      completion.completeExceptionally(failure);
      downstream.onError(failure);
    }

    private void closeReader() {
      try {
        reader.close();
      } catch (IOException closeFailure) {
        completion.completeExceptionally(closeFailure);
      }
    }
  }

  private static FilePathAwareMappingStrategy<PaymentRecord> mappingStrategy(Path path) {
    FilePathAwareMappingStrategy<PaymentRecord> strategy = new FilePathAwareMappingStrategy<>(path);
    strategy.setType(PaymentRecord.class);
    return strategy;
  }

  private void captureHeader(CsvPaymentsInputFile input, FilePathAwareMappingStrategy<PaymentRecord> strategy) {
    try (CursorUtf8Reader source = openCursor(input, 0); CSVReader reader = csvReader(source)) {
      strategy.captureHeader(reader);
    } catch (Exception failure) {
      throw new IllegalArgumentException("Unable to read CSV header from " + input.filepath(), failure);
    }
  }

  private long headerOffset(CsvPaymentsInputFile input) {
    try (CursorUtf8Reader source = openCursor(input, 0); CSVReader reader = csvReader(source)) {
      if (reader.readNext() == null) {
        throw new IllegalArgumentException("CSV source is missing its header: " + input.filepath());
      }
      return source.recordBoundaryPosition();
    } catch (Exception failure) {
      throw new IllegalArgumentException("Unable to locate CSV header boundary in " + input.filepath(), failure);
    }
  }

  private CursorUtf8Reader openCursor(CsvPaymentsInputFile input, long offset) throws IOException {
    if (input.contentRef().isAbsent()) {
      return new CursorUtf8Reader(new FileBytes(input.filepath()), offset);
    }
    PayloadReference reference = input.contentRef().asOptional().orElseThrow();
    var future = openRead.apply(reference).toCompletableFuture();
    final ObjectReadSession session;
    try {
      session = future.get(readTimeout.toMillis(), TimeUnit.MILLISECONDS);
    } catch (TimeoutException failure) {
      future.whenComplete((lateSession, ignored) -> {
        if (lateSession != null) lateSession.close();
      });
      throw new IOException("Timed out opening pinned CSV object", failure);
    } catch (InterruptedException failure) {
      Thread.currentThread().interrupt();
      future.whenComplete((lateSession, ignored) -> {
        if (lateSession != null) lateSession.close();
      });
      throw new IOException("Interrupted opening pinned CSV object", failure);
    } catch (ExecutionException failure) {
      throw new IOException("Unable to open pinned CSV object", failure.getCause());
    }
    return new CursorUtf8Reader(new SessionBytes(session, reference.sizeBytes(), readTimeout), offset);
  }

  private static CSVReader csvReader(Reader source) {
    return new CSVReaderBuilder(source)
        .withVerifyReader(false)
        .withCSVParser(new CSVParserBuilder()
            .withSeparator(',')
            .withIgnoreLeadingWhiteSpace(true)
            .build())
        .build();
  }

  private static boolean isEmpty(String[] row) {
    return row.length == 0 || (row.length == 1 && row[0].isBlank());
  }

  private static SourceVersion sourceVersion(CsvPaymentsInputFile input, String snapshotIdentity) {
    if (!input.contentRef().isAbsent()) {
      PayloadReference reference = input.contentRef().asOptional().orElseThrow();
      if (!"s3".equals(reference.provider()) || !snapshotIdentity.equals(reference.checksum())
          || reference.sizeBytes() < 0
          || !input.filepath().equals(CsvPaymentFileObjectMapper.logicalS3Path(reference.container(), reference.key()))) {
        throw new IllegalArgumentException("Pinned CSV object reference does not match its source identity");
      }
      if (reference.sizeBytes() > MAX_S3_CSV_BYTES) {
        throw new IllegalArgumentException("Pinned CSV object exceeds the 1 MiB pilot input limit");
      }
      return new SourceVersion(referenceIdentity(reference), reference.sizeBytes(), 0L);
    }
    Path path = input.filepath();
    try {
      if (!Files.isRegularFile(path)) {
        throw new IllegalArgumentException("Pinned CSV snapshot is missing: " + path);
      }
      return new SourceVersion(snapshotIdentity, Files.size(path), Files.getLastModifiedTime(path).toMillis());
    } catch (IOException failure) {
      throw new IllegalArgumentException("Unable to validate pinned CSV snapshot: " + path, failure);
    }
  }

  private static String requireIdentity(String identity) {
    if (identity == null || identity.isBlank()) {
      throw new IllegalArgumentException("Paged CSV input requires a pinned source identity");
    }
    return identity;
  }

  private static String referenceIdentity(PayloadReference reference) {
    try {
      var encoded = new java.io.ByteArrayOutputStream();
      try (DataOutputStream out = new DataOutputStream(encoded)) {
        out.writeUTF(reference.provider());
        out.writeUTF(reference.container());
        out.writeUTF(reference.key());
        out.writeUTF(reference.version() == null ? "" : reference.version());
        out.writeUTF(reference.checksum());
        out.writeLong(reference.sizeBytes());
        out.writeUTF(reference.connectorOrigin().map(origin -> origin.bindingName().toString()).orElse(""));
        out.writeUTF(reference.connectorOrigin().map(origin -> origin.operation().toString()).orElse(""));
        out.writeUTF(reference.connectorOrigin()
            .flatMap(origin -> origin.configuration()).map(config -> config.digest()).orElse(""));
      }
      return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(encoded.toByteArray()));
    } catch (IOException | NoSuchAlgorithmException failure) {
      throw new IllegalStateException("Unable to fingerprint pinned CSV object reference", failure);
    }
  }

  private static String encode(Cursor cursor) {
    try {
      java.io.ByteArrayOutputStream bytes = new java.io.ByteArrayOutputStream();
      try (DataOutputStream out = new DataOutputStream(bytes)) {
        out.writeInt(CHECKPOINT_VERSION);
        out.writeUTF(cursor.executionSourceIdentity());
        out.writeUTF(cursor.version().snapshotIdentity());
        out.writeLong(cursor.version().sizeBytes());
        out.writeLong(cursor.version().lastModifiedEpochMs());
        out.writeLong(cursor.offset());
      }
      return Base64.getUrlEncoder().withoutPadding().encodeToString(bytes.toByteArray());
    } catch (IOException impossible) {
      throw new IllegalStateException("Unable to encode CSV checkpoint", impossible);
    }
  }

  private static Cursor decode(String token, String executionSourceIdentity, SourceVersion current) {
    try (DataInputStream in = new DataInputStream(
        new java.io.ByteArrayInputStream(Base64.getUrlDecoder().decode(token)))) {
      int version = in.readInt();
      Cursor decoded = new Cursor(
          in.readUTF(),
          new SourceVersion(in.readUTF(), in.readLong(), in.readLong()),
          in.readLong());
      if (version != CHECKPOINT_VERSION || in.available() != 0) {
        throw new IllegalArgumentException("Unsupported OpenCSV checkpoint format");
      }
      if (!decoded.executionSourceIdentity().equals(executionSourceIdentity)) {
        throw new IllegalArgumentException("OpenCSV checkpoint belongs to another logical execution source");
      }
      if (!decoded.version().equals(current)) {
        throw new IllegalArgumentException("Pinned CSV snapshot no longer matches its checkpoint");
      }
      if (decoded.offset() < 0 || decoded.offset() > current.sizeBytes()) {
        throw new IllegalArgumentException("OpenCSV checkpoint offset is outside the pinned snapshot");
      }
      return decoded;
    } catch (IllegalArgumentException failure) {
      throw failure;
    } catch (Exception failure) {
      throw new IllegalArgumentException("Invalid OpenCSV checkpoint", failure);
    }
  }

  private record SourceVersion(String snapshotIdentity, long sizeBytes, long lastModifiedEpochMs) {
  }

  private record Cursor(String executionSourceIdentity, SourceVersion version, long offset) {
  }

  private static final class EmptySubscription implements Flow.Subscription {
    @Override public void request(long n) { }
    @Override public void cancel() { }
  }

  /** UTF-8 reader that never reads beyond the character requested by OpenCSV's line reader. */
  private static final class CursorUtf8Reader extends Reader {
    private final ByteSource source;
    private final ByteBuffer bytes = ByteBuffer.allocate(READ_CHUNK_BYTES);
    private long logicalPosition;
    private int pendingLowSurrogate = -1;
    private int lastCharacter = -1;

    private CursorUtf8Reader(ByteSource source, long offset) throws IOException {
      this.source = source;
      try {
        source.skip(offset);
        logicalPosition = offset;
        bytes.limit(0);
      } catch (IOException | RuntimeException failure) {
        source.close();
        throw failure;
      }
    }

    long position() {
      return logicalPosition;
    }

    long recordBoundaryPosition() {
      try {
        if (lastCharacter == '\r' && peekByte() == '\n') {
          return logicalPosition + 1;
        }
        return logicalPosition;
      } catch (IOException failure) {
        throw new IllegalStateException("Unable to locate CSV record boundary", failure);
      }
    }

    @Override
    public int read(char[] target, int offset, int length) throws IOException {
      if (length == 0) {
        return 0;
      }
      int value = read();
      if (value < 0) {
        return -1;
      }
      target[offset] = (char) value;
      return 1;
    }

    @Override
    public int read() throws IOException {
      if (pendingLowSurrogate >= 0) {
        int value = pendingLowSurrogate;
        pendingLowSurrogate = -1;
        return track(value);
      }
      int first = readByte();
      if (first < 0) {
        return -1;
      }
      int codePoint;
      int minimum;
      int continuationCount;
      if ((first & 0x80) == 0) {
        return track(first);
      } else if ((first & 0xE0) == 0xC0) {
        codePoint = first & 0x1F;
        minimum = 0x80;
        continuationCount = 1;
      } else if ((first & 0xF0) == 0xE0) {
        codePoint = first & 0x0F;
        minimum = 0x800;
        continuationCount = 2;
      } else if ((first & 0xF8) == 0xF0) {
        codePoint = first & 0x07;
        minimum = 0x10000;
        continuationCount = 3;
      } else {
        throw new IOException("Invalid UTF-8 lead byte in CSV source");
      }
      for (int i = 0; i < continuationCount; i++) {
        int next = readByte();
        if (next < 0 || (next & 0xC0) != 0x80) {
          throw new IOException("Invalid UTF-8 continuation byte in CSV source");
        }
        codePoint = (codePoint << 6) | (next & 0x3F);
      }
      if (codePoint < minimum || codePoint > 0x10FFFF
          || (codePoint >= 0xD800 && codePoint <= 0xDFFF)) {
        throw new IOException("Invalid UTF-8 code point in CSV source");
      }
      if (codePoint <= 0xFFFF) {
        return track(codePoint);
      }
      int adjusted = codePoint - 0x10000;
      pendingLowSurrogate = 0xDC00 | (adjusted & 0x3FF);
      return track(0xD800 | (adjusted >>> 10));
    }

    private int readByte() throws IOException {
      if (!bytes.hasRemaining() && !refill()) {
        return -1;
      }
      logicalPosition++;
      return bytes.get() & 0xFF;
    }

    private int peekByte() throws IOException {
      if (!bytes.hasRemaining() && !refill()) {
        return -1;
      }
      return bytes.get(bytes.position()) & 0xFF;
    }

    private boolean refill() throws IOException {
      bytes.clear();
      int read;
      do {
        read = source.read(bytes);
      } while (read == 0);
      bytes.flip();
      return read > 0;
    }

    private int track(int character) {
      lastCharacter = character;
      return character;
    }

    @Override
    public void close() throws IOException {
      source.close();
    }
  }

  private interface ByteSource extends AutoCloseable {
    void skip(long offset) throws IOException;
    int read(ByteBuffer target) throws IOException;
    @Override void close() throws IOException;
  }

  private static final class FileBytes implements ByteSource {
    private final FileChannel channel;

    private FileBytes(Path path) throws IOException {
      channel = FileChannel.open(path, StandardOpenOption.READ);
    }

    @Override public void skip(long offset) throws IOException { channel.position(offset); }
    @Override public int read(ByteBuffer target) throws IOException { return channel.read(target); }
    @Override public void close() throws IOException { channel.close(); }
  }

  private static final class SessionBytes implements ByteSource {
    private final ObjectReadSession session;
    private final long expectedBytes;
    private final Duration readTimeout;
    private ByteBuffer pending = ByteBuffer.allocate(0);
    private long received;

    private SessionBytes(ObjectReadSession session, long expectedBytes, Duration readTimeout) {
      this.session = java.util.Objects.requireNonNull(session);
      this.expectedBytes = expectedBytes;
      this.readTimeout = readTimeout;
    }

    @Override public void skip(long offset) throws IOException {
      if (offset < 0) throw new IOException("Negative CSV checkpoint offset");
      long remaining = offset;
      while (remaining > 0) {
        if (!pending.hasRemaining() && !nextChunk()) {
          throw new IOException("CSV checkpoint exceeds pinned object length");
        }
        int skipped = (int) Math.min(remaining, pending.remaining());
        pending.position(pending.position() + skipped);
        remaining -= skipped;
      }
    }

    @Override public int read(ByteBuffer target) throws IOException {
      if (!pending.hasRemaining() && !nextChunk()) return -1;
      int count = Math.min(target.remaining(), pending.remaining());
      int limit = pending.limit();
      pending.limit(pending.position() + count);
      target.put(pending);
      pending.limit(limit);
      return count;
    }

    private boolean nextChunk() throws IOException {
      try {
        for (int empty = 0; empty < 4; empty++) {
          var next = session.read(READ_CHUNK_BYTES).toCompletableFuture()
              .get(readTimeout.toMillis(), TimeUnit.MILLISECONDS);
          if (next.isEmpty()) {
            if (received != expectedBytes) throw new IOException("Pinned CSV object ended before its declared size");
            pending = ByteBuffer.allocate(0);
            return false;
          }
          pending = next.orElseThrow();
          if (pending.hasRemaining()) {
            if (pending.remaining() > expectedBytes - received) {
              throw new IOException("Pinned CSV object exceeds its declared size");
            }
            received += pending.remaining();
            return true;
          }
        }
        throw new IOException("Pinned CSV object returned repeated empty chunks");
      } catch (IOException failure) {
        throw failure;
      } catch (TimeoutException failure) {
        session.close();
        throw new IOException("Timed out reading pinned CSV object", failure);
      } catch (InterruptedException failure) {
        Thread.currentThread().interrupt();
        session.close();
        throw new IOException("Interrupted reading pinned CSV object", failure);
      } catch (ExecutionException failure) {
        throw new IOException("Unable to read pinned CSV object", failure.getCause());
      } catch (RuntimeException failure) {
        throw new IOException("Unable to read pinned CSV object", failure);
      }
    }

    @Override public void close() { session.close(); }
  }
}
