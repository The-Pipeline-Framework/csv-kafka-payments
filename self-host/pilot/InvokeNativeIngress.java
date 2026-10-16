import org.pipelineframework.aws.durable.AwsDurableIngressHandler;
import org.pipelineframework.aws.durable.model.AwsDurableExecutionInput;
import org.pipelineframework.config.pipeline.PipelineJson;

/** Invokes the unchanged published native ingress; Lambda transport is the local fixture. */
public class InvokeNativeIngress {
 public static void main(String[] args) throws Exception {
  var input=PipelineJson.mapper().readValue(System.in.readAllBytes(),AwsDurableExecutionInput.class);
  var response=new AwsDurableIngressHandler().handleRequest(input,null);
  System.out.println(PipelineJson.mapper().writeValueAsString(response));
 }
}
