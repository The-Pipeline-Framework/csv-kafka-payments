package org.pipelineframework.csv.service;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.grpc.ManagedChannelBuilder;
import io.quarkus.test.common.http.TestHTTPResource;
import io.quarkus.test.junit.QuarkusTest;
import jakarta.inject.Inject;
import java.net.URI;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Flow;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.pipelineframework.csv.domain.CsvPaymentsInputFile;
import org.pipelineframework.csv.grpc.MutinyProcessCsvPaymentsInputServiceGrpc;
import org.pipelineframework.csv.grpc.PipelineTypes;
import org.pipelineframework.csv.grpc.ProcessCsvPaymentsInputSvc;
import org.pipelineframework.orchestrator.PipelineOrchestratorConfig;
import org.pipelineframework.orchestrator.PipelineReleaseIdentityResolver;
import org.pipelineframework.paging.PagedSourceCompletion;
import org.pipelineframework.paging.PagedSourceRequest;
import org.pipelineframework.paging.RemotePageFrame;
import org.pipelineframework.paging.RemotePagedSourceBridge;

/** Exercises the generated source host over a real gRPC connection. */
@QuarkusTest
class RemotePagedCsvGrpcIT {
  @TestHTTPResource URI endpoint;
  @TempDir Path tempDir;
  @Inject RemotePagedSourceBridge bridge;
  @Inject PipelineReleaseIdentityResolver releaseIdentity;
  @Inject PipelineOrchestratorConfig orchestratorConfig;

  @Test
  void twoRecordsFinishOnlyAfterDemandAndRpcClosure() throws Exception {
    Path csv = tempDir.resolve("two-payments.csv");
    Files.writeString(csv, "ID,Recipient,Amount,Currency\n"
        + "first,A,1.00,EUR\n"
        + "second,B,2.00,EUR\n");
    var input = new CsvPaymentsInputFile(csv, tempDir, "snapshot-two");
    var request = new PagedSourceRequest<>(input, "execution-source", Optional.empty(), 2);
    var wireInput = PipelineTypes.CsvPaymentsInputFile.newBuilder()
        .setCsvFolderPath(tempDir.toString())
        .setFilepath(csv.toString())
        .setSourceIdentity("snapshot-two")
        .build();
    var wireRequest = ProcessCsvPaymentsInputSvc.CsvPaymentsInputPageRequest.newBuilder()
        .setInput(wireInput)
        .setSourceIdentity(request.sourceIdentity())
        .setMaxRecords(request.maxRecords())
        .setPipelineId(releaseIdentity.pipelineId(orchestratorConfig))
        .setContractVersion(releaseIdentity.contractVersion())
        .setReleaseVersion(releaseIdentity.releaseVersion(orchestratorConfig))
        .setCatalogFingerprint(bridge.catalogFingerprint())
        .build();
    var channel = ManagedChannelBuilder.forAddress(endpoint.getHost(), endpoint.getPort())
        .usePlaintext().build();
    try {
      var stub = MutinyProcessCsvPaymentsInputServiceGrpc.newMutinyStub(channel);
      var page = bridge.open(request, stub.remoteOpenPage(wireRequest), frame -> {
        if (frame.hasItem()) {
          return new RemotePageFrame.Item<>(frame.getItem().getCsvId());
        }
        var result = frame.getCompletion();
        return new RemotePageFrame.Completion<>(new PagedSourceCompletion(
            result.getConsumedRecords(),
            result.hasNextCheckpoint() ? Optional.of(result.getNextCheckpoint()) : Optional.empty(),
            result.getExhausted()));
      });
      var subscriber = new DemandSubscriber();
      page.items().subscribe(subscriber);
      assertTrue(subscriber.subscription.await(5, TimeUnit.SECONDS));
      assertTrue(subscriber.items.isEmpty());
      assertFalse(page.completion().toCompletableFuture().isDone());

      subscriber.current.get().request(1);
      assertTrue(subscriber.first.await(5, TimeUnit.SECONDS),
          () -> "first item missing; stream failure=" + subscriber.failure.get()
              + "; cause=" + Optional.ofNullable(subscriber.failure.get()).map(Throwable::getCause));
      assertEquals(List.of("first"), subscriber.items);
      assertFalse(page.completion().toCompletableFuture().isDone());

      subscriber.current.get().request(1);
      assertTrue(subscriber.done.await(10, TimeUnit.SECONDS));
      assertNull(subscriber.failure.get());
      assertEquals(List.of("first", "second"), subscriber.items);
      var completion = page.completion().toCompletableFuture().get(5, TimeUnit.SECONDS);
      assertEquals(2, completion.consumedRecords());
      assertTrue(completion.exhausted());
    } finally {
      channel.shutdownNow().awaitTermination(5, TimeUnit.SECONDS);
    }
  }

  private static final class DemandSubscriber implements Flow.Subscriber<String> {
    private final AtomicReference<Flow.Subscription> current = new AtomicReference<>();
    private final AtomicReference<Throwable> failure = new AtomicReference<>();
    private final List<String> items = new CopyOnWriteArrayList<>();
    private final CountDownLatch subscription = new CountDownLatch(1);
    private final CountDownLatch first = new CountDownLatch(1);
    private final CountDownLatch done = new CountDownLatch(1);

    @Override public void onSubscribe(Flow.Subscription value) {
      current.set(value);
      subscription.countDown();
    }

    @Override public void onNext(String value) {
      items.add(value);
      first.countDown();
    }

    @Override public void onError(Throwable error) {
      failure.set(error);
      done.countDown();
    }

    @Override public void onComplete() {
      done.countDown();
    }
  }
}
