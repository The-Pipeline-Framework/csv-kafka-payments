package org.pipelineframework.csv.provider;

import org.pipelineframework.awaitable.sqs.SqsAwaitCompletionEnvelope;
import org.pipelineframework.awaitable.sqs.SqsAwaitDispatchEnvelope;
import org.pipelineframework.config.pipeline.PipelineJson;
import org.pipelineframework.csv.dto.PaymentRecordDto;
import org.pipelineframework.csv.dto.PaymentStatusDto;
import org.pipelineframework.csv.grpc.PipelineTypes;
import org.pipelineframework.csv.service.PaymentProviderAwaitRequestHandler;
import org.pipelineframework.csv.service.PaymentProviderServiceMock;
import org.pipelineframework.csv.transport.generated.PaymentRecord_024f11ffbae6RestMapper;
import org.pipelineframework.csv.transport.generated.PaymentStatus_f62a18a739f2RestMapper;

/** Explicitly binds the two existing CSV Await wire representations to the provider. */
final class PaymentProviderSqsLambdaPayload {
  private static final String PROTO_INPUT = PipelineTypes.PaymentRecord.class.getCanonicalName();
  private static final String PROTO_OUTPUT = PipelineTypes.PaymentStatus.class.getCanonicalName();
  private static final String REST_INPUT = PaymentRecordDto.class.getName();
  private static final String REST_OUTPUT = PaymentStatusDto.class.getName();
  private static final PaymentRecord_024f11ffbae6RestMapper REST_REQUEST =
      new PaymentRecord_024f11ffbae6RestMapper();
  private static final PaymentStatus_f62a18a739f2RestMapper REST_RESPONSE =
      new PaymentStatus_f62a18a739f2RestMapper();

  private PaymentProviderSqsLambdaPayload() {
  }

  static SqsAwaitCompletionEnvelope complete(SqsAwaitDispatchEnvelope dispatch,
      PaymentProviderServiceMock provider) {
    if (PROTO_INPUT.equals(dispatch.inputType()) && PROTO_OUTPUT.equals(dispatch.outputType())) {
      return PaymentProviderAwaitRequestHandler.complete(dispatch, provider);
    }
    if (REST_INPUT.equals(dispatch.inputType()) && REST_OUTPUT.equals(dispatch.outputType())) {
      PaymentRecordDto request = PipelineJson.mapper()
          .convertValue(dispatch.requestPayload(), PaymentRecordDto.class);
      var status = PaymentProviderAwaitRequestHandler.process(REST_REQUEST.fromExternal(request), provider);
      Object response = PipelineJson.mapper().convertValue(REST_RESPONSE.toExternal(status), Object.class);
      return new SqsAwaitCompletionEnvelope(
          dispatch.tenantId(), dispatch.interactionId(), dispatch.correlationId(),
          dispatch.resumeToken(), dispatch.interactionId(), response, "csv-payments-sqs-mock-provider");
    }
    throw new IllegalArgumentException("Unsupported CSV provider Await wire type pair");
  }
}
