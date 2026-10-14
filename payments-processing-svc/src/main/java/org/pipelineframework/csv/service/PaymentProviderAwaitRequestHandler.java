package org.pipelineframework.csv.service;

import com.google.protobuf.util.JsonFormat;
import org.pipelineframework.awaitable.sqs.SqsAwaitCompletionEnvelope;
import org.pipelineframework.awaitable.sqs.SqsAwaitDispatchEnvelope;
import org.pipelineframework.config.pipeline.PipelineJson;
import org.pipelineframework.csv.domain.PaymentRecord;
import org.pipelineframework.csv.domain.PaymentStatus;
import org.pipelineframework.csv.domain.PipelineDomainProtoAdapters;
import org.pipelineframework.csv.grpc.PipelineTypes;

/** Business conversion shared by the self-host provider and its finite SQS Lambda adapter. */
public final class PaymentProviderAwaitRequestHandler {
  private PaymentProviderAwaitRequestHandler() {
  }

  public static SqsAwaitCompletionEnvelope complete(
      SqsAwaitDispatchEnvelope dispatch, PaymentProviderService provider) {
    PaymentRecord record = PipelineDomainProtoAdapters.fromProto(paymentRecord(dispatch.requestPayload()));
    PaymentStatus status = process(record, provider);
    return new SqsAwaitCompletionEnvelope(
        dispatch.tenantId(), dispatch.interactionId(), dispatch.correlationId(),
        dispatch.resumeToken(), dispatch.interactionId(),
        PaymentProviderAwaitTransportPayload.protobufJson(PipelineDomainProtoAdapters.toProto(status)),
        "csv-payments-sqs-mock-provider");
  }

  public static PaymentStatus process(PaymentRecord record, PaymentProviderService provider) {
    validatePaymentRecord(record);
    return provider.processPayment(record);
  }

  private static void validatePaymentRecord(PaymentRecord record) {
    if (record == null) {
      throw new IllegalArgumentException("SQS await payment request payload must contain a PaymentRecord");
    }
    if (record.amount() == null || record.recipient() == null || record.recipient().isBlank()
        || record.currency() == null || record.id() == null) {
      throw new IllegalArgumentException("PaymentRecord must include id, amount, recipient, and currency");
    }
  }

  private static PipelineTypes.PaymentRecord paymentRecord(Object payload) {
    try {
      var builder = PipelineTypes.PaymentRecord.newBuilder();
      JsonFormat.parser().ignoringUnknownFields().merge(PipelineJson.mapper().writeValueAsString(payload), builder);
      return builder.build();
    } catch (Exception e) {
      throw new IllegalArgumentException("SQS await payment request payload must contain a v3 PaymentRecord", e);
    }
  }
}
