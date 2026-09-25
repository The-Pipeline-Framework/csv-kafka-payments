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
import java.util.Base64;
import java.util.Optional;
import java.util.concurrent.CancellationException;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.Flow;

import com.opencsv.CSVParserBuilder;
import com.opencsv.CSVReader;
import com.opencsv.CSVReaderBuilder;
import org.pipelineframework.csv.common.domain.CsvPaymentsStableIdSupport;
import org.pipelineframework.csv.common.domain.FilePathAwareMappingStrategy;
import org.pipelineframework.csv.common.domain.PaymentRecord;
import org.pipelineframework.csv.domain.CsvPaymentsInputFile;
import org.pipelineframework.paging.PagedSourceCompletion;
import org.pipelineframework.paging.PagedSourceRequest;
import org.pipelineframework.paging.PagedSourceStream;

/** OpenCSV-backed logical-record cursor with exact UTF-8 byte checkpoints. */
final class OpenCsvPagedPaymentSource {
  private static final int CHECKPOINT_VERSION = 1;

  PagedSourceStream<PaymentRecord> open(PagedSourceRequest<CsvPaymentsInputFile> request) {
    Path path = request.input().filepath();
    String snapshotIdentity = requireIdentity(request.input().sourceIdentity());
    SourceVersion version = sourceVersion(path, snapshotIdentity);
    Cursor cursor = request.checkpoint()
        .map(token -> decode(token, request.sourceIdentity(), version))
        .orElseGet(() -> new Cursor(request.sourceIdentity(), version, headerOffset(path)));
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
      captureHeader(request.input().filepath(), strategy);
      CursorUtf8Reader source = new CursorUtf8Reader(request.input().filepath(), cursor.offset());
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
          long boundary = source.recordBoundaryPosition();
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
        long boundary = source.recordBoundaryPosition();
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

  private static void captureHeader(Path path, FilePathAwareMappingStrategy<PaymentRecord> strategy) {
    try (CursorUtf8Reader source = new CursorUtf8Reader(path, 0); CSVReader reader = csvReader(source)) {
      strategy.captureHeader(reader);
    } catch (Exception failure) {
      throw new IllegalArgumentException("Unable to read CSV header from " + path, failure);
    }
  }

  private static long headerOffset(Path path) {
    try (CursorUtf8Reader source = new CursorUtf8Reader(path, 0); CSVReader reader = csvReader(source)) {
      if (reader.readNext() == null) {
        throw new IllegalArgumentException("CSV source is missing its header: " + path);
      }
      return source.recordBoundaryPosition();
    } catch (Exception failure) {
      throw new IllegalArgumentException("Unable to locate CSV header boundary in " + path, failure);
    }
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

  private static SourceVersion sourceVersion(Path path, String snapshotIdentity) {
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
    private static final int BUFFER_SIZE = 8 * 1024;
    private final FileChannel channel;
    private final ByteBuffer bytes = ByteBuffer.allocate(BUFFER_SIZE);
    private long logicalPosition;
    private int pendingLowSurrogate = -1;
    private int lastCharacter = -1;

    private CursorUtf8Reader(Path path, long offset) throws IOException {
      channel = FileChannel.open(path, StandardOpenOption.READ);
      channel.position(offset);
      logicalPosition = offset;
      bytes.limit(0);
    }

    long position() {
      return logicalPosition;
    }

    long recordBoundaryPosition() {
      try {
        if (lastCharacter == '\r' && peekByte() == '\n') {
          bytes.get();
          logicalPosition++;
          lastCharacter = '\n';
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
        read = channel.read(bytes);
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
      channel.close();
    }
  }
}
