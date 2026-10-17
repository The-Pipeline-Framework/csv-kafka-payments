package org.pipelineframework.csv.service;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.Flow;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import org.junit.jupiter.api.Test;
import org.pipelineframework.connector.ObjectReadSession;
import org.pipelineframework.connector.ConnectorBindingName;
import org.pipelineframework.connector.ConnectorOperationIdentity;
import org.pipelineframework.connector.ConnectorOperationKind;
import org.pipelineframework.connector.ConnectorPayloadOrigin;
import org.pipelineframework.connector.ConnectorProviderId;
import org.pipelineframework.csv.common.domain.PaymentRecord;
import org.pipelineframework.csv.domain.CsvPaymentsInputFile;
import org.pipelineframework.paging.PagedSourceRequest;
import org.pipelineframework.repository.PayloadReference;

class S3PagedCsvReaderTest {
  private static final String KEY = "incoming/8d30325f-4980-42df-93d4-256759760431.csv";
  // A virtual absolute path prevents Jackson from resolving this identity against the process directory.
  private static final Path LOGICAL_PATH = Path.of("/s3", "csv-payments-input", KEY);
  private static final byte[] CONTENT = ("ID,Recipient,Amount,Currency\r\n"
      + "first,María,1.00,EUR\r\n"
      + "second,Zoë,2.00,USD\r\n"
      + "third,Álvaro,3.00,EUR\r\n").getBytes(StandardCharsets.UTF_8);

  @Test
  void reopensEveryPageWithStableLogicalIdentityAndClosesEachSession() throws Exception {
    var fixture = new Sessions(CONTENT);
    // REST serializes Path as a URI; the virtual identity must survive that actual wire codec.
    var json = new com.fasterxml.jackson.databind.ObjectMapper().findAndRegisterModules();
    var original = input("etag-one", CONTENT.length);
    var restoredPath = json.readValue(json.writeValueAsBytes(original.filepath()), Path.class);
    var restoredFolder = json.readValue(json.writeValueAsBytes(original.csvFolderPath()), Path.class);
    var input = new CsvPaymentsInputFile(restoredPath, restoredFolder,
        original.sourceIdentity(), original.contentRef());
    Optional<String> checkpoint = Optional.empty();
    List<PaymentRecord> rows = new ArrayList<>();
    int pages = 0;
    while (true) {
      var source = new OpenCsvPagedPaymentSource(fixture::open);
      var page = source.open(new PagedSourceRequest<>(input, "run-one", checkpoint, 1));
      var subscriber = new DemandSubscriber();
      page.items().subscribe(subscriber);
      assertTrue(subscriber.items.isEmpty());
      subscriber.subscription.request(1);
      rows.addAll(subscriber.items);
      var completion = page.completion().toCompletableFuture().get(5, TimeUnit.SECONDS);
      pages++;
      if (completion.exhausted()) break;
      checkpoint = completion.nextCheckpoint();
    }
    assertEquals(3, pages);
    assertEquals(List.of("first", "second", "third"), rows.stream().map(PaymentRecord::getCsvId).toList());
    assertTrue(rows.stream().allMatch(row -> LOGICAL_PATH.equals(row.getCsvPaymentsInputFilePath())));
    assertEquals(3, rows.stream().map(PaymentRecord::getId).distinct().count());
    assertEquals(fixture.opened.get(), fixture.closed.get());
    assertFalse(java.nio.file.Files.exists(LOGICAL_PATH));

    var replay = new OpenCsvPagedPaymentSource(fixture::open).open(new PagedSourceRequest<>(input, "run-one", Optional.empty(), 1));
    var subscriber = new DemandSubscriber();
    replay.items().subscribe(subscriber);
    subscriber.subscription.request(1);
    replay.completion().toCompletableFuture().get(5, TimeUnit.SECONDS);
    assertEquals(rows.getFirst().getId(), subscriber.items.getFirst().getId());
    assertEquals(fixture.opened.get(), fixture.closed.get());
  }

  @Test
  void changedObjectIdentityAndOversizeFailBeforeOpeningSessions() throws Exception {
    var fixture = new Sessions(CONTENT);
    var source = new OpenCsvPagedPaymentSource(fixture::open);
    var first = source.open(new PagedSourceRequest<>(input("etag-one", CONTENT.length), "run-one", Optional.empty(), 1));
    var subscriber = new DemandSubscriber();
    first.items().subscribe(subscriber);
    subscriber.subscription.request(1);
    String checkpoint = first.completion().toCompletableFuture().get(5, TimeUnit.SECONDS).nextCheckpoint().orElseThrow();
    int opened = fixture.opened.get();
    assertThrows(IllegalArgumentException.class, () -> source.open(new PagedSourceRequest<>(
        input("etag-two", CONTENT.length), "run-one", Optional.of(checkpoint), 1)));
    assertThrows(IllegalArgumentException.class, () -> source.open(new PagedSourceRequest<>(
        inputWithKey("incoming/another-object.csv", "etag-one", CONTENT.length),
        "run-one", Optional.of(checkpoint), 1)));
    assertThrows(IllegalArgumentException.class, () -> source.open(new PagedSourceRequest<>(
        input("etag-one", 1024L * 1024 + 1), "run-one", Optional.empty(), 1)));
    assertEquals(opened, fixture.opened.get());
    assertEquals(fixture.opened.get(), fixture.closed.get());
  }

  @Test
  void forgedLogicalPathFailsBeforeOpeningSessions() {
    var fixture = new Sessions(CONTENT);
    var original = input("etag-one", CONTENT.length);
    var forgedPath = Path.of("/s3", "foreign-bucket", KEY);
    var forged = new CsvPaymentsInputFile(forgedPath, forgedPath.getParent(),
        original.sourceIdentity(), original.contentRef());

    assertThrows(IllegalArgumentException.class, () ->
        new OpenCsvPagedPaymentSource(fixture::open).open(new PagedSourceRequest<>(
            forged, "run-one", Optional.empty(), 1)));
    assertEquals(0, fixture.opened.get());
  }

  @Test
  void cancellationClosesTheActiveReadSession() throws Exception {
    var fixture = new Sessions(CONTENT);
    var page = new OpenCsvPagedPaymentSource(fixture::open).open(new PagedSourceRequest<>(
        input("etag-one", CONTENT.length), "run-one", Optional.empty(), 2));
    var subscriber = new DemandSubscriber();
    page.items().subscribe(subscriber);
    subscriber.subscription.cancel();
    assertTrue(page.completion().toCompletableFuture().isCompletedExceptionally());
    assertEquals(fixture.opened.get(), fixture.closed.get());
  }

  @Test
  void declaredObjectLengthMismatchFailsAndClosesReadSession() throws Exception {
    for (long declaredSize : List.of((long) CONTENT.length - 1, (long) CONTENT.length + 1)) {
      var fixture = new Sessions(CONTENT);
      var source = new OpenCsvPagedPaymentSource(fixture::open);
      var page = source.open(new PagedSourceRequest<>(
          input("etag-one", declaredSize), "run-one", Optional.empty(), 100));
      var subscriber = new DemandSubscriber();
      page.items().subscribe(subscriber);
      subscriber.subscription.request(100);
      assertTrue(page.completion().toCompletableFuture().isCompletedExceptionally());
      assertEquals(fixture.opened.get(), fixture.closed.get());
    }
  }

  @Test
  void oneEmptyChunkDoesNotTruncateTheObject() throws Exception {
    var fixture = new Sessions(CONTENT, true, false);
    var page = new OpenCsvPagedPaymentSource(fixture::open).open(new PagedSourceRequest<>(
        input("etag-one", CONTENT.length), "run-one", Optional.empty(), 100));
    var subscriber = new DemandSubscriber();
    page.items().subscribe(subscriber);
    subscriber.subscription.request(100);

    assertEquals(List.of("first", "second", "third"),
        subscriber.items.stream().map(PaymentRecord::getCsvId).toList());
    assertTrue(page.completion().toCompletableFuture().get(5, TimeUnit.SECONDS).exhausted());
    assertEquals(fixture.opened.get(), fixture.closed.get());
  }

  @Test
  void readFailureDuringHeaderClosesTheOpenedSession() {
    var fixture = new Sessions(CONTENT, false, true);

    assertThrows(IllegalArgumentException.class, () ->
        new OpenCsvPagedPaymentSource(fixture::open).open(new PagedSourceRequest<>(
            input("etag-one", CONTENT.length), "run-one", Optional.empty(), 1)));
    assertEquals(fixture.opened.get(), fixture.closed.get());
  }

  @Test
  void openingTimeoutClosesALateReadSession() {
    var pending = new CompletableFuture<ObjectReadSession>();
    var closed = new AtomicInteger();
    var source = new OpenCsvPagedPaymentSource(reference -> pending, Duration.ofMillis(20));

    assertThrows(IllegalArgumentException.class, () -> source.open(new PagedSourceRequest<>(
        input("etag-one", CONTENT.length), "run-one", Optional.empty(), 1)));
    pending.complete(new ObjectReadSession() {
      @Override public CompletableFuture<Optional<ByteBuffer>> read(int maxBytes) {
        return CompletableFuture.completedFuture(Optional.empty());
      }
      @Override public void close() { closed.incrementAndGet(); }
    });
    assertEquals(1, closed.get());
  }

  @Test
  void readTimeoutAbortsTheActiveSession() {
    var closed = new AtomicInteger();
    var source = new OpenCsvPagedPaymentSource(reference -> CompletableFuture.completedFuture(new ObjectReadSession() {
      @Override public CompletableFuture<Optional<ByteBuffer>> read(int maxBytes) {
        return new CompletableFuture<>();
      }
      @Override public void close() { closed.incrementAndGet(); }
    }), Duration.ofMillis(20));

    assertThrows(IllegalArgumentException.class, () -> source.open(new PagedSourceRequest<>(
        input("etag-one", CONTENT.length), "run-one", Optional.empty(), 1)));
    assertTrue(closed.get() >= 1);
  }

  private static CsvPaymentsInputFile input(String etag, long size) {
    return inputWithKey(KEY, etag, size);
  }

  private static CsvPaymentsInputFile inputWithKey(String key, String etag, long size) {
    Path logicalPath = Path.of("/s3", "csv-payments-input", key);
    var origin = new ConnectorPayloadOrigin(
        ConnectorBindingName.of("csv-input-s3"),
        new ConnectorOperationIdentity(
            ConnectorProviderId.of("s3.objects"), "s3", ConnectorOperationKind.OBJECT_SOURCE, 1),
        1, Optional.empty());
    var reference = new PayloadReference("s3", "csv-payments-input", key, null, "raw", etag,
        size, null, Map.of(), Optional.of(origin));
    return new CsvPaymentsInputFile(logicalPath, logicalPath.getParent(), etag,
        org.pipelineframework.type.CanonicalFieldValue.of(reference));
  }

  private static final class Sessions {
    private final byte[] bytes;
    private final AtomicInteger opened = new AtomicInteger();
    private final AtomicInteger closed = new AtomicInteger();
    private final boolean initialEmpty;
    private final boolean failRead;

    private Sessions(byte[] bytes) { this(bytes, false, false); }

    private Sessions(byte[] bytes, boolean initialEmpty, boolean failRead) {
      this.bytes = bytes;
      this.initialEmpty = initialEmpty;
      this.failRead = failRead;
    }

    CompletableFuture<ObjectReadSession> open(PayloadReference reference) {
      opened.incrementAndGet();
      return CompletableFuture.completedFuture(new ObjectReadSession() {
        private int offset;
        private boolean done;
        private boolean sentEmpty;
        @Override public CompletableFuture<Optional<ByteBuffer>> read(int maxBytes) {
          if (done) throw new IllegalStateException("closed");
          if (failRead) return CompletableFuture.failedFuture(new IllegalStateException("read failed"));
          if (initialEmpty && !sentEmpty) {
            sentEmpty = true;
            return CompletableFuture.completedFuture(Optional.of(ByteBuffer.allocate(0)));
          }
          if (offset == bytes.length) return CompletableFuture.completedFuture(Optional.empty());
          int end = Math.min(offset + Math.min(maxBytes, 7), bytes.length);
          ByteBuffer chunk = ByteBuffer.wrap(bytes, offset, end - offset);
          offset = end;
          return CompletableFuture.completedFuture(Optional.of(chunk));
        }
        @Override public void close() {
          if (!done) { done = true; closed.incrementAndGet(); }
        }
      });
    }
  }

  private static final class DemandSubscriber implements Flow.Subscriber<PaymentRecord> {
    private final List<PaymentRecord> items = new ArrayList<>();
    private Flow.Subscription subscription;
    @Override public void onSubscribe(Flow.Subscription value) { subscription = value; }
    @Override public void onNext(PaymentRecord item) { items.add(item); }
    @Override public void onError(Throwable failure) { }
    @Override public void onComplete() { }
  }
}
