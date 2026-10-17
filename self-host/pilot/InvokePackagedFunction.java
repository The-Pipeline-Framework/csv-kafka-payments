import java.io.*;
import java.lang.reflect.Proxy;
import java.nio.charset.StandardCharsets;
import com.amazonaws.services.lambda.runtime.Context;
import io.quarkus.amazon.lambda.runtime.QuarkusStreamHandler;

/** External Lambda event-delivery fixture; executes the actual packaged handler. */
public class InvokePackagedFunction {
 public static void main(String[] args) throws Exception {
  Context context=(Context)Proxy.newProxyInstance(Context.class.getClassLoader(),new Class[]{Context.class},
   (p,m,a)-> { if(m.getReturnType()==String.class)return "local-worker-fixture";if(m.getReturnType()==int.class)return 60000;return null; });
  var handler=new QuarkusStreamHandler();
  var input=new BufferedReader(new InputStreamReader(System.in,StandardCharsets.UTF_8));
  for(String line; (line=input.readLine())!=null;) {
   var out=new ByteArrayOutputStream();
   handler.handleRequest(new ByteArrayInputStream(line.getBytes(StandardCharsets.UTF_8)),out,context);
   System.out.println("FUNCTION_RESULT="+out.toString(StandardCharsets.UTF_8));System.out.flush();
  }
  System.exit(0);
 }
}
