package org.pipelineframework.csv.service;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.Flow;
import java.util.concurrent.TimeUnit;
import java.util.stream.IntStream;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.pipelineframework.csv.common.domain.PaymentRecord;
import org.pipelineframework.csv.domain.CsvPaymentsInputFile;
import org.pipelineframework.paging.PagedSourceRequest;
import org.pipelineframework.paging.PagedSourceStream;

class OpenCsvPagedPaymentSourceTest {
  @TempDir Path tempDir;

  @Test
  void pagesLogicalRecordsWithoutReadingAheadAcrossQuotedNewlines() throws Exception {
    Path csv = tempDir.resolve("payments.csv");
    Files.writeString(csv, "ID,Recipient,Amount,Currency\n"
        + "first,\"María\nGarcía\",10.00,EUR\n"
        + "second,Zoë,20.00,USD\n");
    CsvPaymentsInputFile input = new CsvPaymentsInputFile(csv, tempDir, "snapshot-1");
    OpenCsvPagedPaymentSource source = new OpenCsvPagedPaymentSource();

    PagedSourceStream<PaymentRecord> first = source.open(new PagedSourceRequest<>(
        input, "execution-source", Optional.empty(), 1));
    DemandSubscriber firstSubscriber = new DemandSubscriber();
    first.items().subscribe(firstSubscriber);
    assertTrue(firstSubscriber.items.isEmpty());
    assertFalse(first.completion().toCompletableFuture().isDone());

    firstSubscriber.subscription.request(1);
    assertEquals(List.of("first"), firstSubscriber.items.stream().map(PaymentRecord::getCsvId).toList());
    assertEquals("María\nGarcía", firstSubscriber.items.getFirst().getRecipient());
    var firstCompletion = first.completion().toCompletableFuture().get(5, TimeUnit.SECONDS);
    assertFalse(firstCompletion.exhausted());

    PagedSourceStream<PaymentRecord> second = source.open(new PagedSourceRequest<>(
        input, "execution-source", firstCompletion.nextCheckpoint(), 1));
    DemandSubscriber secondSubscriber = new DemandSubscriber();
    second.items().subscribe(secondSubscriber);
    secondSubscriber.subscription.request(1);
    assertEquals(List.of("second"), secondSubscriber.items.stream().map(PaymentRecord::getCsvId).toList());
    assertEquals("Zoë", secondSubscriber.items.getFirst().getRecipient());
    assertTrue(second.completion().toCompletableFuture().get(5, TimeUnit.SECONDS).exhausted());
  }

  @Test
  void resumesAfterCrLfAtTheNextLogicalRecord() throws Exception {
    Path csv = tempDir.resolve("payments-crlf.csv");
    Files.writeString(csv, "ID,Recipient,Amount,Currency\r\n"
        + "first,A,1.00,EUR\r\n"
        + "second,B,2.00,EUR\r\n");
    CsvPaymentsInputFile input = new CsvPaymentsInputFile(csv, tempDir, "snapshot-crlf");
    OpenCsvPagedPaymentSource source = new OpenCsvPagedPaymentSource();

    PagedSourceStream<PaymentRecord> first = source.open(new PagedSourceRequest<>(
        input, "execution-source", Optional.empty(), 1));
    DemandSubscriber firstSubscriber = new DemandSubscriber();
    first.items().subscribe(firstSubscriber);
    firstSubscriber.subscription.request(1);
    var firstCompletion = first.completion().toCompletableFuture().get(5, TimeUnit.SECONDS);

    PagedSourceStream<PaymentRecord> second = source.open(new PagedSourceRequest<>(
        input, "execution-source", firstCompletion.nextCheckpoint(), 1));
    DemandSubscriber secondSubscriber = new DemandSubscriber();
    second.items().subscribe(secondSubscriber);
    secondSubscriber.subscription.request(1);
    var secondCompletion = second.completion().toCompletableFuture().get(5, TimeUnit.SECONDS);

    assertEquals(List.of("first"), firstSubscriber.items.stream().map(PaymentRecord::getCsvId).toList());
    assertEquals(List.of("second"), secondSubscriber.items.stream().map(PaymentRecord::getCsvId).toList());
    assertEquals(1, firstCompletion.consumedRecords());
    assertEquals(1, secondCompletion.consumedRecords());
    assertTrue(secondCompletion.exhausted());
  }

  @Test
  void crLfBoundaryDoesNotSkipTheFollowingEmptyLogicalRecord() throws Exception {
    Path csv = tempDir.resolve("payments-crlf-empty.csv");
    Files.writeString(csv, "ID,Recipient,Amount,Currency\r\n"
        + "first,A,1.00,EUR\r\n"
        + "\r\n"
        + "second,B,2.00,EUR\r\n");
    CsvPaymentsInputFile input = new CsvPaymentsInputFile(csv, tempDir, "snapshot-crlf-empty");
    OpenCsvPagedPaymentSource source = new OpenCsvPagedPaymentSource();

    PagedSourceStream<PaymentRecord> first = source.open(new PagedSourceRequest<>(
        input, "execution-source", Optional.empty(), 1));
    DemandSubscriber firstSubscriber = new DemandSubscriber();
    first.items().subscribe(firstSubscriber);
    firstSubscriber.subscription.request(1);
    var firstCompletion = first.completion().toCompletableFuture().get(5, TimeUnit.SECONDS);

    PagedSourceStream<PaymentRecord> empty = source.open(new PagedSourceRequest<>(
        input, "execution-source", firstCompletion.nextCheckpoint(), 1));
    DemandSubscriber emptySubscriber = new DemandSubscriber();
    empty.items().subscribe(emptySubscriber);
    emptySubscriber.subscription.request(1);
    var emptyCompletion = empty.completion().toCompletableFuture().get(5, TimeUnit.SECONDS);

    PagedSourceStream<PaymentRecord> last = source.open(new PagedSourceRequest<>(
        input, "execution-source", emptyCompletion.nextCheckpoint(), 1));
    DemandSubscriber lastSubscriber = new DemandSubscriber();
    last.items().subscribe(lastSubscriber);
    lastSubscriber.subscription.request(1);
    var lastCompletion = last.completion().toCompletableFuture().get(5, TimeUnit.SECONDS);

    PagedSourceStream<PaymentRecord> wideFirst = source.open(new PagedSourceRequest<>(
        input, "execution-source-wide", Optional.empty(), 2));
    DemandSubscriber wideFirstSubscriber = new DemandSubscriber();
    wideFirst.items().subscribe(wideFirstSubscriber);
    wideFirstSubscriber.subscription.request(2);
    var wideFirstCompletion = wideFirst.completion().toCompletableFuture().get(5, TimeUnit.SECONDS);

    PagedSourceStream<PaymentRecord> wideLast = source.open(new PagedSourceRequest<>(
        input, "execution-source-wide", wideFirstCompletion.nextCheckpoint(), 2));
    DemandSubscriber wideLastSubscriber = new DemandSubscriber();
    wideLast.items().subscribe(wideLastSubscriber);
    wideLastSubscriber.subscription.request(2);
    var wideLastCompletion = wideLast.completion().toCompletableFuture().get(5, TimeUnit.SECONDS);

    assertEquals(List.of("first"), firstSubscriber.items.stream().map(PaymentRecord::getCsvId).toList());
    assertTrue(emptySubscriber.items.isEmpty());
    assertEquals(List.of("second"), lastSubscriber.items.stream().map(PaymentRecord::getCsvId).toList());
    assertEquals(1, firstCompletion.consumedRecords());
    assertEquals(1, emptyCompletion.consumedRecords());
    assertEquals(1, lastCompletion.consumedRecords());
    assertTrue(lastCompletion.exhausted());
    assertEquals(List.of("first"), wideFirstSubscriber.items.stream().map(PaymentRecord::getCsvId).toList());
    assertEquals(List.of("second"), wideLastSubscriber.items.stream().map(PaymentRecord::getCsvId).toList());
    assertEquals(2, wideFirstCompletion.consumedRecords());
    assertEquals(1, wideLastCompletion.consumedRecords());
    assertTrue(wideLastCompletion.exhausted());
  }

  @Test
  void rejectsCheckpointWhenPinnedSnapshotChanges() throws Exception {
    Path csv = tempDir.resolve("payments.csv");
    Files.writeString(csv, "ID,Recipient,Amount,Currency\nfirst,A,1.00,EUR\nsecond,B,2.00,EUR\n");
    CsvPaymentsInputFile input = new CsvPaymentsInputFile(csv, tempDir, "snapshot-1");
    OpenCsvPagedPaymentSource source = new OpenCsvPagedPaymentSource();
    PagedSourceStream<PaymentRecord> first = source.open(new PagedSourceRequest<>(
        input, "execution-source", Optional.empty(), 1));
    DemandSubscriber subscriber = new DemandSubscriber();
    first.items().subscribe(subscriber);
    subscriber.subscription.request(1);
    String checkpoint = first.completion().toCompletableFuture().get().nextCheckpoint().orElseThrow();

    Files.writeString(csv, Files.readString(csv) + "third,C,3.00,EUR\n");
    assertThrows(IllegalArgumentException.class, () -> source.open(new PagedSourceRequest<>(
        input, "execution-source", Optional.of(checkpoint), 1)));
  }

  @Test
  void repeatedEmptyNonExhaustedPagesAdvanceWithoutLooping() throws Exception {
    Path csv = tempDir.resolve("payments.csv");
    Files.writeString(csv, "ID,Recipient,Amount,Currency\n\n\nsecond,B,2.00,EUR\n");
    CsvPaymentsInputFile input = new CsvPaymentsInputFile(csv, tempDir, "snapshot-empty-page");
    OpenCsvPagedPaymentSource source = new OpenCsvPagedPaymentSource();

    PagedSourceStream<PaymentRecord> first = source.open(new PagedSourceRequest<>(
        input, "execution-source", Optional.empty(), 1));
    DemandSubscriber subscriber = new DemandSubscriber();
    first.items().subscribe(subscriber);
    subscriber.subscription.request(1);
    var completion = first.completion().toCompletableFuture().get(5, TimeUnit.SECONDS);

    assertTrue(subscriber.items.isEmpty());
    assertFalse(completion.exhausted());
    assertTrue(completion.nextCheckpoint().isPresent());

    PagedSourceStream<PaymentRecord> second = source.open(new PagedSourceRequest<>(
        input, "execution-source", completion.nextCheckpoint(), 1));
    DemandSubscriber secondSubscriber = new DemandSubscriber();
    second.items().subscribe(secondSubscriber);
    secondSubscriber.subscription.request(1);
    var secondCompletion = second.completion().toCompletableFuture().get(5, TimeUnit.SECONDS);
    assertTrue(secondSubscriber.items.isEmpty());
    assertFalse(secondCompletion.exhausted());
    assertTrue(secondCompletion.nextCheckpoint().isPresent());
    assertFalse(secondCompletion.nextCheckpoint().equals(completion.nextCheckpoint()));

    PagedSourceStream<PaymentRecord> third = source.open(new PagedSourceRequest<>(
        input, "execution-source", secondCompletion.nextCheckpoint(), 1));
    DemandSubscriber thirdSubscriber = new DemandSubscriber();
    third.items().subscribe(thirdSubscriber);
    thirdSubscriber.subscription.request(1);
    assertEquals(List.of("second"), thirdSubscriber.items.stream().map(PaymentRecord::getCsvId).toList());
    assertTrue(third.completion().toCompletableFuture().get(5, TimeUnit.SECONDS).exhausted());
  }

  @Test
  void oneSubmittedTenThousandRecordSourceResumesInBoundedPages() throws Exception {
    Path csv = tempDir.resolve("payments-10000.csv");
    StringBuilder content = new StringBuilder("ID,Recipient,Amount,Currency\n");
    IntStream.range(0, 10_000).forEach(index -> content.append("payment-")
        .append(index).append(",Recipient ").append(index).append(",1.00,EUR\n"));
    Files.writeString(csv, content);
    CsvPaymentsInputFile input = new CsvPaymentsInputFile(csv, tempDir, "snapshot-10000");
    OpenCsvPagedPaymentSource source = new OpenCsvPagedPaymentSource();
    Optional<String> checkpoint = Optional.empty();
    List<String> ids = new ArrayList<>(10_000);
    int pages = 0;

    while (true) {
      PagedSourceStream<PaymentRecord> page = source.open(new PagedSourceRequest<>(
          input, "execution-source", checkpoint, 1_000));
      DemandSubscriber subscriber = new DemandSubscriber();
      page.items().subscribe(subscriber);
      subscriber.subscription.request(Long.MAX_VALUE);
      ids.addAll(subscriber.items.stream().map(PaymentRecord::getCsvId).toList());
      var completion = page.completion().toCompletableFuture().get(5, TimeUnit.SECONDS);
      pages++;
      if (completion.exhausted()) {
        break;
      }
      checkpoint = completion.nextCheckpoint();
    }

    assertEquals(10, pages);
    assertEquals(10_000, ids.size());
    assertEquals(10_000, ids.stream().distinct().count());
    assertEquals("payment-0", ids.getFirst());
    assertEquals("payment-9999", ids.getLast());
  }

  private static final class DemandSubscriber implements Flow.Subscriber<PaymentRecord> {
    private final List<PaymentRecord> items = new ArrayList<>();
    private Flow.Subscription subscription;

    @Override public void onSubscribe(Flow.Subscription subscription) { this.subscription = subscription; }
    @Override public void onNext(PaymentRecord item) { items.add(item); }
    @Override public void onError(Throwable throwable) { throw new AssertionError(throwable); }
    @Override public void onComplete() { }
  }
}
