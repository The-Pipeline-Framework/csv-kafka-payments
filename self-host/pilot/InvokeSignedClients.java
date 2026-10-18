import java.io.*;
import java.lang.reflect.*;
import java.time.Duration;
import java.util.*;
import java.util.concurrent.*;
import io.smallrye.mutiny.Uni;
import io.smallrye.mutiny.Multi;
import io.quarkus.amazon.lambda.runtime.QuarkusStreamHandler;
import org.eclipse.microprofile.rest.client.inject.RestClient;
import org.pipelineframework.config.pipeline.PipelineJson;

/** External authentication fixture only: invokes the actual packaged generated CDI clients. */
public class InvokeSignedClients {
 @jakarta.annotation.Priority(1000)
 public static class AddQuery implements jakarta.ws.rs.client.ClientRequestFilter {
  @Override public void filter(jakarta.ws.rs.client.ClientRequestContext request) {
   request.setUri(java.net.URI.create(request.getUri().toASCIIString()+"?z=%2B&a=hello%20world&a=%C3%A9&empty="));
  }
 }

 public static void main(String[] args) throws Exception {
  new QuarkusStreamHandler();
  var mapper=io.quarkus.arc.Arc.container().instance(com.fasterxml.jackson.databind.ObjectMapper.class).get();
  var reader=new BufferedReader(new InputStreamReader(System.in));
  for(String line; (line=reader.readLine())!=null;) {
   var command=PipelineJson.mapper().readTree(line);
   var result=new LinkedHashMap<String,Object>();
   try {
    String suffix=command.path("client").asText("CsvPaymentsInput");
    var type=Class.forName("org.pipelineframework.csv.service.pipeline.Process"+suffix+"RestClient");
    Object client=io.quarkus.arc.Arc.container().instance(type,RestClient.LITERAL).get();
    var method=Arrays.stream(type.getMethods()).filter(m->m.getName().equals(command.path("page").asBoolean()?"openPage":"process")).findFirst().orElseThrow();
    int count=command.path("concurrency").asInt(1);
    try(var executor=Executors.newFixedThreadPool(count)) {
    var start=new CountDownLatch(1);
    var futures=new ArrayList<CompletableFuture<Void>>();
    for(int i=0;i<count;i++) {
     final int index=i;
     futures.add(CompletableFuture.runAsync(()->{
      try {
       start.await();
       var entity=command.path("input").deepCopy();
       if(count>1 && entity instanceof com.fasterxml.jackson.databind.node.ObjectNode object && object.has("filepath"))
        object.put("filepath",object.path("filepath").asText()+"-"+index);
       Object[] parameters=new Object[method.getParameterCount()];
       for(int n=0;n<parameters.length;n++) {
        parameters[n]=method.getParameterTypes()[n]==String.class ? List.of("fixture-v"+index,"false","BYPASS").get(n)
            : mapper.convertValue(entity,mapper.getTypeFactory().constructType(method.getGenericParameterTypes()[n]));
       }
       Object response=method.invoke(client,parameters);
       if(response instanceof Uni<?> uni)uni.await().atMost(Duration.ofSeconds(15));
       else if(response instanceof Multi<?> multi)multi.collect().asList().await().atMost(Duration.ofSeconds(15));
      } catch(Exception e){throw new CompletionException(e);}
     },executor));
    }
    start.countDown();
    CompletableFuture.allOf(futures.toArray(CompletableFuture[]::new)).join();
    }
    result.put("ok",true);result.put("count",count);
   } catch(Throwable failure) {
    Throwable cause=failure;
    while(cause.getCause()!=null)cause=cause.getCause();
    result.put("ok",false);result.put("type",cause.getClass().getName());result.put("message",String.valueOf(cause.getMessage()));
   }
   System.out.println("SIGNING_RESULT="+PipelineJson.mapper().writeValueAsString(result));System.out.flush();
  }
  System.exit(0);
 }
}
