/*
 * Copyright (c) 2026 Mariano Barcia
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package org.pipelineframework.csv.service;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.mockConstruction;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.google.protobuf.util.JsonFormat;
import java.math.BigDecimal;
import java.nio.file.Path;
import java.time.Duration;
import java.util.Currency;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.pipelineframework.awaitable.AwaitTelemetry;
import org.pipelineframework.awaitable.sqs.SqsAwaitDispatchEnvelope;
import org.pipelineframework.config.pipeline.PipelineJson;
import org.pipelineframework.csv.domain.PaymentRecord;
import org.pipelineframework.csv.domain.PaymentStatus;
import org.pipelineframework.csv.domain.PipelineDomainProtoAdapters;
import software.amazon.awssdk.services.sqs.SqsClient;
import software.amazon.awssdk.services.sqs.model.DeleteMessageRequest;
import software.amazon.awssdk.services.sqs.model.Message;
import software.amazon.awssdk.services.sqs.model.SendMessageRequest;
import software.amazon.awssdk.services.sqs.model.SendMessageResponse;

class PaymentProviderSqsAwaitMockTest {

  @Test
  void providerUsesBoundedConcurrentReceiveLoopsForTheBurstHarness() {
    assertEquals(8, PaymentProviderSqsAwaitMock.RECEIVE_LOOP_CONCURRENCY);
  }

  @Test
  void failedCompletionSendIsNotCountedOrDeletedAndCanBeRetried() throws Exception {
    SqsClient client = mock(SqsClient.class);
    when(client.sendMessage(any(SendMessageRequest.class)))
        .thenThrow(new IllegalStateException("SQS unavailable"))
        .thenReturn(SendMessageResponse.builder().messageId("completion-1").build());
    PaymentProviderConfig providerConfig = new PaymentProviderServiceMockTest.FakePaymentProviderConfig();
    PaymentRecord record = new PaymentRecord(
        UUID.randomUUID(), "csv-1", "Ada", new BigDecimal("12.34"),
        Currency.getInstance("EUR"), Path.of("/tmp/payments.csv"));
    Object payload = PipelineJson.mapper().readValue(
        JsonFormat.printer().print(PipelineDomainProtoAdapters.toProto(record)), Object.class);
    String body = PipelineJson.mapper().writeValueAsString(new SqsAwaitDispatchEnvelope(
        "tenant-1", "execution-1", "interaction-1", "correlation-1", "AwaitPaymentProvider",
        System.currentTimeMillis() + 60_000L, PaymentRecord.class.getName(), PaymentStatus.class.getName(),
        "resume-token", payload, Map.of()));
    Message request = Message.builder().messageId("request-1").receiptHandle("receipt-1").body(body).build();
    var config = new PaymentProviderSqsAwaitMock.SqsProviderConfig(
        true, Optional.of("http://localhost:4566/queue/requests"),
        Optional.of("http://localhost:4566/queue/responses"), Optional.empty(), Optional.empty(),
        Duration.ZERO, Duration.ofSeconds(30), 1, 1);
    var handleMessage = PaymentProviderSqsAwaitMock.class.getDeclaredMethod(
        "handleMessage", String.class, Message.class, PaymentProviderSqsAwaitMock.SqsProviderConfig.class);
    handleMessage.setAccessible(true);

    try (var telemetryConstruction = mockConstruction(AwaitTelemetry.class)) {
      var provider = new PaymentProviderSqsAwaitMock(
          new PaymentProviderServiceMock(providerConfig), providerConfig, client);
      AwaitTelemetry telemetry = telemetryConstruction.constructed().getFirst();

      handleMessage.invoke(provider, config.requestQueueUrl().orElseThrow(), request, config);

      verify(telemetry, never()).recordProviderCompletionDispatched();
      verify(client, never()).deleteMessage(any(DeleteMessageRequest.class));

      handleMessage.invoke(provider, config.requestQueueUrl().orElseThrow(), request, config);

      var order = inOrder(client, telemetry);
      order.verify(client).sendMessage(any(SendMessageRequest.class));
      order.verify(client).sendMessage(any(SendMessageRequest.class));
      order.verify(telemetry).recordProviderCompletionDispatched();
      order.verify(client).deleteMessage(DeleteMessageRequest.builder()
          .queueUrl(config.requestQueueUrl().orElseThrow()).receiptHandle("receipt-1").build());
      verify(telemetry).recordProviderCompletionDispatched();
    }
  }

  @Test
  void visibilityTimeoutCoversConcurrentProcessingForOneReceivedBatch() {
    PaymentProviderConfig providerConfig = new PaymentProviderServiceMockTest.FakePaymentProviderConfig() {
      @Override
      public long timeoutMillis() {
        return 1_250L;
      }

      @Override
      public long responseDelayMillis() {
        return 500L;
      }
    };
    PaymentProviderSqsAwaitMock mock = new PaymentProviderSqsAwaitMock(null, providerConfig, null);
    PaymentProviderSqsAwaitMock.SqsProviderConfig sqsConfig =
        new PaymentProviderSqsAwaitMock.SqsProviderConfig(
            true,
            Optional.of("http://localhost:4566/queue/requests"),
            Optional.of("http://localhost:4566/queue/responses"),
            Optional.empty(),
            Optional.empty(),
            Duration.ZERO,
            Duration.ofSeconds(1),
            1,
            10);

    assertEquals(2, mock.visibilityTimeoutSeconds(sqsConfig));
  }
}
