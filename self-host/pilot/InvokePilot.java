import java.io.*;
import java.lang.reflect.Proxy;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import com.amazonaws.services.lambda.runtime.Context;
import io.quarkus.amazon.lambda.runtime.QuarkusStreamHandler;
import org.pipelineframework.config.pipeline.PipelineJson;
import org.pipelineframework.aws.durable.*;
import org.pipelineframework.aws.durable.model.*;

/** Synthetic AWS event delivery only; semantic admission uses published native actions/stores. */
public class InvokePilot {
 private static void inject(Object bean,String name,Object value) throws Exception {
  var field=bean.getClass().getDeclaredField(name);field.setAccessible(true);field.set(bean,value);
 }
 private static com.amazonaws.services.lambda.runtime.events.SQSEvent sqsEvent(com.fasterxml.jackson.databind.JsonNode input) throws Exception {
  // AWS's serializer maps "Records" / "eventSourceARN" to Java bean names.
  // Adapt only event fields here; PipelineJson stays strict for pipeline values.
  if(input==null || !input.isObject() || !input.path("Records").isArray())
   throw new IllegalArgumentException("Fixture SQS event requires a Records array");
  var eventObject=(com.fasterxml.jackson.databind.node.ObjectNode)input.deepCopy();
  eventObject.set("records",eventObject.remove("Records"));
  for(var record:eventObject.path("records")) {
   if(!record.isObject() || !record.path("eventSourceARN").isTextual())
    throw new IllegalArgumentException("Fixture SQS record requires eventSourceARN");
   var object=(com.fasterxml.jackson.databind.node.ObjectNode)record;
   object.set("eventSourceArn",object.remove("eventSourceARN"));
  }
  return PipelineJson.mapper().treeToValue(eventObject,com.amazonaws.services.lambda.runtime.events.SQSEvent.class);
 }
 public static void main(String[] args) throws Exception {
  Context context=(Context)Proxy.newProxyInstance(Context.class.getClassLoader(),new Class[]{Context.class},
   (p,m,a)-> { if(m.getReturnType()==String.class)return "local-pilot-event";if(m.getReturnType()==int.class)return 60000;return null; });
  var handler=new QuarkusStreamHandler();
  var input=new BufferedReader(new InputStreamReader(System.in,StandardCharsets.UTF_8));
  for(String line; (line=input.readLine())!=null;) {
   var command=PipelineJson.mapper().readTree(line);
   String result;
   try {
   if(command.has("bootstrapRelease")) {
    // Local fixture setup calls the unchanged native admin service in-process.
    // No bootstrap path or route exception is added to the deployed HTTP application.
    var setup=command.get("bootstrapRelease");
    var admin=io.quarkus.arc.Arc.container().instance(org.pipelineframework.orchestrator.release.HostedReleaseAdminResource.class).get();
    var token="Bearer "+System.getenv("TPF_ADMIN_TOKEN");
    var registered=admin.register(setup.path("tenant").asText(),setup.path("pipeline").asText(),token,
      new org.pipelineframework.orchestrator.release.dto.HostedReleaseRegisterRequest(setup.path("descriptor").asText())).await().atMost(Duration.ofSeconds(30));
    var activated=admin.activate(setup.path("tenant").asText(),setup.path("pipeline").asText(),setup.path("release").asText(),token).await().atMost(Duration.ofSeconds(30));
    result=PipelineJson.mapper().writeValueAsString(java.util.Map.of("registeredStatus",registered.getStatus(),"activatedStatus",activated.getStatus(),"registered",registered.getEntity(),"activated",activated.getEntity()));
   } else if(command.has("diagnoseReference")) {
    var mapper=io.quarkus.arc.Arc.container().instance(com.fasterxml.jackson.databind.ObjectMapper.class).get();
    try {
     var reference=mapper.treeToValue(command.get("diagnoseReference"),org.pipelineframework.repository.PayloadReference.class);
     result=PipelineJson.mapper().writeValueAsString(java.util.Map.of("parsed",true,"hasOrigin",reference.connectorOrigin().isPresent()));
    } catch(Exception failure) {
     result=PipelineJson.mapper().writeValueAsString(java.util.Map.of("parsed",false,"type",failure.getClass().getName(),"message",failure.getMessage()));
    }
   } else if(command.has("nativeWork") || command.has("nativeCompletion")) {
    // Fixture supplies only SQS event delivery and CDI injection. Published handlers own processing.
    var service=io.quarkus.arc.Arc.container().instance(org.pipelineframework.PipelineExecutionService.class).get();
    var event=sqsEvent(command.get(command.has("nativeWork") ? "nativeWork" : "nativeCompletion"));
    Object action; Object nativeHandler;
    if(command.has("nativeWork")) {
     action=new org.pipelineframework.orchestrator.SqsWorkItemAction();
     inject(action,"pipelineExecutionService",service);
     nativeHandler=new AwsDurableWorkItemHandler();
    } else {
     action=new org.pipelineframework.awaitable.sqs.SqsAwaitCompletionAction();
     inject(action,"executionService",service);
     nativeHandler=new AwsDurableAwaitCompletionHandler();
    }
    inject(nativeHandler,"action",action);
    @SuppressWarnings("unchecked") var batch=(com.amazonaws.services.lambda.runtime.RequestHandler<com.amazonaws.services.lambda.runtime.events.SQSEvent,com.amazonaws.services.lambda.runtime.events.SQSBatchResponse>)nativeHandler;
    result=PipelineJson.mapper().writeValueAsString(batch.handleRequest(event,context));
   } else if(command.has("nativeSubmit")) {
    var submitted=PipelineJson.mapper().treeToValue(command.get("nativeSubmit"),AwsDurableExecutionInput.class);
    var control=io.quarkus.arc.Arc.container().instance(org.pipelineframework.orchestrator.PipelineControlPlane.class).get();
    try(var dynamo=software.amazon.awssdk.services.dynamodb.DynamoDbClient.builder()
      .endpointOverride(java.net.URI.create(System.getenv("AWS_ENDPOINT_URL_DYNAMODB")))
      .region(software.amazon.awssdk.regions.Region.US_EAST_1).build()) {
     var actions=new AwsDurableControlPlaneActions(control,new AwsDurableCallbackBindingRepository(dynamo,"local-unused-callback-bindings"),
      // Fixture-only decoder for this NATIVE package. The AWS_DURABLE package separately
      // proves its generated AwsDurableInputDecoder against the strict published codec.
      json -> {try{return PipelineJson.mapper().readValue(json,org.pipelineframework.csv.domain.CsvPaymentsInputFile.class);}catch(Exception e){throw new IllegalArgumentException(e);}},
      PipelineJson.mapper(),Duration.ofSeconds(20),Duration.ofDays(90));
     result=PipelineJson.mapper().writeValueAsString(actions.invoke(AwsDurableActionRequest.submit(submitted)));
    }
   } else {
    var out=new ByteArrayOutputStream();
    handler.handleRequest(new ByteArrayInputStream(line.getBytes(StandardCharsets.UTF_8)),out,context);
    result=out.toString(StandardCharsets.UTF_8);
    if ("true".equals(System.getenv("PILOT_DIAGNOSTIC"))) {
      var readerField=io.quarkus.amazon.lambda.runtime.AmazonLambdaRecorder.class.getDeclaredField("objectReader");
      readerField.setAccessible(true);
      var reader=(io.quarkus.amazon.lambda.runtime.LambdaInputReader<?>)readerField.get(null);
      var parsed=reader.readValue(new ByteArrayInputStream(line.getBytes(StandardCharsets.UTF_8)));
      var event=(com.amazonaws.services.lambda.runtime.events.APIGatewayV2HTTPEvent)parsed;
      System.out.println("EVENT_DIAGNOSTIC="+event.getRawPath()+" "+event.getHeaders()+" "+event.getRequestContext().getHttp().getMethod());
    }
   }
   } catch(Exception failure) {
    failure.printStackTrace(System.out);
    result=PipelineJson.mapper().writeValueAsString(java.util.Map.of("fixtureCommandFailed",true,
      "type",failure.getClass().getName(),"message",String.valueOf(failure.getMessage())));
   }
   System.out.println("PILOT_RESULT="+result);System.out.flush();
  }
  System.exit(0);
 }
}
