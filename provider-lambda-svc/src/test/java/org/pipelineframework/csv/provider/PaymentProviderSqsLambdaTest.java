package org.pipelineframework.csv.provider;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.amazonaws.services.lambda.runtime.events.SQSEvent;
import com.google.protobuf.util.JsonFormat;
import java.math.BigDecimal;
import java.nio.file.Path;
import java.util.Currency;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.pipelineframework.awaitable.sqs.SqsAwaitCompletionEnvelope;
import org.pipelineframework.awaitable.sqs.SqsAwaitDispatchEnvelope;
import org.pipelineframework.config.pipeline.PipelineJson;
import org.pipelineframework.csv.domain.PaymentRecord;
import org.pipelineframework.csv.domain.PaymentStatus;
import org.pipelineframework.csv.domain.PipelineDomainProtoAdapters;
import org.pipelineframework.csv.dto.PaymentRecordDto;
import org.pipelineframework.csv.dto.PaymentStatusDto;
import org.pipelineframework.csv.grpc.PipelineTypes;
import org.pipelineframework.csv.service.PaymentProviderConfig;
import org.pipelineframework.csv.service.PaymentProviderServiceMock;
import org.pipelineframework.csv.transport.generated.PaymentRecord_024f11ffbae6RestMapper;
import org.pipelineframework.csv.transport.generated.PaymentStatus_f62a18a739f2RestMapper;
import software.amazon.awssdk.services.sqs.SqsClient;
import software.amazon.awssdk.services.sqs.model.SendMessageRequest;
import software.amazon.awssdk.services.sqs.model.SendMessageResponse;

class PaymentProviderSqsLambdaTest {
  private static final String RESPONSE_QUEUE = "https://sqs.example.test/response";

  @Test
  void publishesExactCompletionAndAcknowledgesOnlySuccessfulRecords() throws Exception {
    SqsClient sqs = mock(SqsClient.class);
    when(sqs.sendMessage(any(SendMessageRequest.class)))
        .thenReturn(SendMessageResponse.builder().messageId("completion-1").build());
    var handler = new PaymentProviderSqsLambda(provider(), sqs, RESPONSE_QUEUE);

    var result = handler.handleRequest(event(message("good", dispatchBodyRest()),
        message("bad", "{not-json")), null);

    assertEquals(1, result.getBatchItemFailures().size());
    assertEquals("bad", result.getBatchItemFailures().getFirst().getItemIdentifier());
    ArgumentCaptor<SendMessageRequest> sent = ArgumentCaptor.forClass(SendMessageRequest.class);
    verify(sqs).sendMessage(sent.capture());
    assertEquals(RESPONSE_QUEUE, sent.getValue().queueUrl());
    var completion = PipelineJson.mapper().readValue(sent.getValue().messageBody(),
        SqsAwaitCompletionEnvelope.class);
    assertEquals("tenant-1", completion.tenantId());
    assertEquals("interaction-1", completion.interactionId());
    assertEquals("correlation-1", completion.correlationId());
    assertEquals("resume-token", completion.resumeToken());
    assertEquals("interaction-1", completion.idempotencyKey());
    assertEquals("csv-payments-sqs-mock-provider", completion.actor());
    var approved = (PaymentStatus.Approved) restStatus(completion);
    assertEquals(paymentRecord(), approved.value().paymentRecord());
    assertEquals("Complete", approved.value().status());
  }

  @Test
  void preservesBusinessRejectionAsUnapprovedStatus() throws Exception {
    SqsClient sqs = mock(SqsClient.class);
    when(sqs.sendMessage(any(SendMessageRequest.class)))
        .thenReturn(SendMessageResponse.builder().messageId("completion-rejected").build());
    PaymentProviderConfig config = mock(PaymentProviderConfig.class);
    when(config.permitsPerSecond()).thenReturn(1000.0);
    when(config.timeoutMillis()).thenReturn(5000L);
    when(config.providerRejectProbability()).thenReturn(1.0);
    var handler = new PaymentProviderSqsLambda(new PaymentProviderServiceMock(config), sqs, RESPONSE_QUEUE);

    assertTrue(handler.handleRequest(event(message("rejected", dispatchBodyRest())), null)
        .getBatchItemFailures().isEmpty());
    ArgumentCaptor<SendMessageRequest> sent = ArgumentCaptor.forClass(SendMessageRequest.class);
    verify(sqs).sendMessage(sent.capture());
    var completion = PipelineJson.mapper().readValue(sent.getValue().messageBody(),
        SqsAwaitCompletionEnvelope.class);
    var unapproved = (PaymentStatus.Unapproved) restStatus(completion);
    assertEquals(paymentRecord(), unapproved.value().paymentRecord());
    assertEquals("Rejected", unapproved.value().status());
  }

  @Test
  void retainsLegacyProtobufWireForTheSelfHostRequestIdentity() throws Exception {
    SqsClient sqs = mock(SqsClient.class);
    when(sqs.sendMessage(any(SendMessageRequest.class)))
        .thenReturn(SendMessageResponse.builder().messageId("completion-proto").build());
    var handler = new PaymentProviderSqsLambda(provider(), sqs, RESPONSE_QUEUE);

    assertTrue(handler.handleRequest(event(message("proto", dispatchBodyProto())), null)
        .getBatchItemFailures().isEmpty());
    ArgumentCaptor<SendMessageRequest> sent = ArgumentCaptor.forClass(SendMessageRequest.class);
    verify(sqs).sendMessage(sent.capture());
    var completion = PipelineJson.mapper().readValue(sent.getValue().messageBody(),
        SqsAwaitCompletionEnvelope.class);
    assertTrue(protoStatus(completion).hasApproved());
  }

  @Test
  void unknownWireIdentityRetriesWithoutPublishing() throws Exception {
    SqsClient sqs = mock(SqsClient.class);
    var handler = new PaymentProviderSqsLambda(provider(), sqs, RESPONSE_QUEUE);
    String unknown = dispatchBody("unknown.Payment", PaymentStatusDto.class.getName(),
        PipelineJson.mapper().convertValue(new PaymentRecord_024f11ffbae6RestMapper()
            .toExternal(paymentRecord()), Object.class));
    var result = handler.handleRequest(event(message("unknown", unknown)), null);
    assertEquals("unknown", result.getBatchItemFailures().getFirst().getItemIdentifier());
    verify(sqs, times(0)).sendMessage(any(SendMessageRequest.class));
  }

  @Test
  void emptyBatchSucceedsAndUnidentifiedFailureFailsWholeInvocation() throws Exception {
    SqsClient sqs = mock(SqsClient.class);
    var handler = new PaymentProviderSqsLambda(provider(), sqs, RESPONSE_QUEUE);
    assertTrue(handler.handleRequest(event(), null).getBatchItemFailures().isEmpty());
    assertThrows(IllegalArgumentException.class,
        () -> handler.handleRequest(event(message(null, "{not-json")), null));
    assertThrows(IllegalArgumentException.class,
        () -> handler.handleRequest(event(message(null, dispatchBodyRest())), null));
    verify(sqs, times(0)).sendMessage(any(SendMessageRequest.class));
  }

  @Test
  void failedSendRetriesOnlyThatRecordAndReplayRetainsCompletionIdentity() throws Exception {
    SqsClient sqs = mock(SqsClient.class);
    when(sqs.sendMessage(any(SendMessageRequest.class)))
        .thenThrow(new IllegalStateException("SQS unavailable"))
        .thenReturn(SendMessageResponse.builder().messageId("completion-1").build())
        .thenReturn(SendMessageResponse.builder().messageId("completion-2").build());
    var handler = new PaymentProviderSqsLambda(provider(), sqs, RESPONSE_QUEUE);
    var request = event(message("request-1", dispatchBodyRest()));

    var first = handler.handleRequest(request, null);
    assertEquals("request-1", first.getBatchItemFailures().getFirst().getItemIdentifier());
    assertTrue(handler.handleRequest(request, null).getBatchItemFailures().isEmpty());
    assertTrue(handler.handleRequest(request, null).getBatchItemFailures().isEmpty());

    ArgumentCaptor<SendMessageRequest> sent = ArgumentCaptor.forClass(SendMessageRequest.class);
    verify(sqs, times(3)).sendMessage(sent.capture());
    assertEquals(sent.getAllValues().get(1).messageBody(), sent.getAllValues().get(2).messageBody());
  }

  private static PaymentProviderServiceMock provider() {
    PaymentProviderConfig config = mock(PaymentProviderConfig.class);
    when(config.permitsPerSecond()).thenReturn(1000.0);
    when(config.timeoutMillis()).thenReturn(5000L);
    return new PaymentProviderServiceMock(config);
  }

  private static PipelineTypes.PaymentStatus protoStatus(SqsAwaitCompletionEnvelope completion) throws Exception {
    var builder = PipelineTypes.PaymentStatus.newBuilder();
    JsonFormat.parser().merge(PipelineJson.mapper().writeValueAsString(completion.responsePayload()), builder);
    return builder.build();
  }

  private static SQSEvent event(SQSEvent.SQSMessage... messages) {
    SQSEvent event = new SQSEvent();
    event.setRecords(List.of(messages));
    return event;
  }

  private static SQSEvent.SQSMessage message(String id, String body) {
    SQSEvent.SQSMessage message = new SQSEvent.SQSMessage();
    message.setMessageId(id);
    message.setBody(body);
    return message;
  }

  private static PaymentStatus restStatus(SqsAwaitCompletionEnvelope completion) {
    PaymentStatusDto dto = PipelineJson.mapper().convertValue(completion.responsePayload(), PaymentStatusDto.class);
    return new PaymentStatus_f62a18a739f2RestMapper().fromExternal(dto);
  }

  private static PaymentRecord paymentRecord() {
    return new PaymentRecord(UUID.fromString("123e4567-e89b-12d3-a456-426614174000"),
        "csv-1", "Ada", new BigDecimal("12.34"), Currency.getInstance("EUR"),
        Path.of("/tmp/payments.csv"));
  }

  private static String dispatchBodyRest() throws Exception {
    PaymentRecordDto dto = new PaymentRecord_024f11ffbae6RestMapper().toExternal(paymentRecord());
    return dispatchBody(PaymentRecordDto.class.getName(), PaymentStatusDto.class.getName(),
        PipelineJson.mapper().convertValue(dto, Object.class));
  }

  private static String dispatchBodyProto() throws Exception {
    Object payload = PipelineJson.mapper().readValue(
        JsonFormat.printer().print(PipelineDomainProtoAdapters.toProto(paymentRecord())), Object.class);
    return dispatchBody(PipelineTypes.PaymentRecord.class.getCanonicalName(),
        PipelineTypes.PaymentStatus.class.getCanonicalName(), payload);
  }

  private static String dispatchBody(String inputType, String outputType, Object payload) throws Exception {
    return PipelineJson.mapper().writeValueAsString(new SqsAwaitDispatchEnvelope(
        "tenant-1", "execution-1", "interaction-1", "correlation-1", "AwaitPaymentProvider",
        System.currentTimeMillis() + 60_000L, inputType, outputType,
        "resume-token", payload, Map.of()));
  }
}
