package org.pipelineframework.csv.provider;

import com.amazonaws.services.lambda.runtime.Context;
import com.amazonaws.services.lambda.runtime.RequestHandler;
import com.amazonaws.services.lambda.runtime.events.SQSBatchResponse;
import com.amazonaws.services.lambda.runtime.events.SQSEvent;
import jakarta.inject.Inject;
import jakarta.inject.Named;
import java.util.ArrayList;
import java.util.List;
import org.eclipse.microprofile.config.inject.ConfigProperty;
import org.jboss.logging.Logger;
import org.pipelineframework.awaitable.sqs.SqsAwaitDispatchEnvelope;
import org.pipelineframework.config.pipeline.PipelineJson;
import org.pipelineframework.csv.service.PaymentProviderServiceMock;
import software.amazon.awssdk.services.sqs.SqsClient;
import software.amazon.awssdk.services.sqs.model.SendMessageRequest;

/** Finite provider invocation for an SQS request event source mapping. */
@Named("csv-provider")
public class PaymentProviderSqsLambda implements RequestHandler<SQSEvent, SQSBatchResponse> {
  private static final Logger LOG = Logger.getLogger(PaymentProviderSqsLambda.class);

  @Inject
  PaymentProviderServiceMock paymentProvider;

  @Inject
  SqsClient sqsClient;

  @ConfigProperty(name = "csv-payments.payment-provider.sqs.response-queue-url")
  String responseQueueUrl;

  public PaymentProviderSqsLambda() {
  }

  PaymentProviderSqsLambda(PaymentProviderServiceMock paymentProvider, SqsClient sqsClient,
      String responseQueueUrl) {
    this.paymentProvider = paymentProvider;
    this.sqsClient = sqsClient;
    this.responseQueueUrl = responseQueueUrl;
  }

  @Override
  public SQSBatchResponse handleRequest(SQSEvent event, Context context) {
    if (event == null || event.getRecords() == null) {
      throw new IllegalArgumentException("SQS provider event must contain records");
    }
    for (SQSEvent.SQSMessage message : event.getRecords()) {
      if (message == null || message.getMessageId() == null || message.getMessageId().isBlank()) {
        throw new IllegalArgumentException("SQS provider record has no message ID");
      }
    }
    List<SQSBatchResponse.BatchItemFailure> failures = new ArrayList<>();
    for (SQSEvent.SQSMessage message : event.getRecords()) {
      try {
        publishCompletion(message);
      } catch (Exception e) {
        LOG.errorf("CSV provider request failed id=%s failureType=%s",
            message == null ? null : message.getMessageId(), e.getClass().getSimpleName());
        failures.add(new SQSBatchResponse.BatchItemFailure(message.getMessageId()));
      }
    }
    return new SQSBatchResponse(failures);
  }

  private void publishCompletion(SQSEvent.SQSMessage message) throws Exception {
    if (message == null || message.getBody() == null) {
      throw new IllegalArgumentException("SQS provider request body is missing");
    }
    var dispatch = PipelineJson.mapper().readValue(message.getBody(), SqsAwaitDispatchEnvelope.class);
    var completion = PaymentProviderSqsLambdaPayload.complete(dispatch, paymentProvider);
    sqsClient.sendMessage(SendMessageRequest.builder()
        .queueUrl(responseQueueUrl)
        .messageBody(PipelineJson.mapper().writeValueAsString(completion))
        .build());
  }
}
