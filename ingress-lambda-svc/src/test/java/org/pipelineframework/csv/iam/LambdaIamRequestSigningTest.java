package org.pipelineframework.csv.iam;

import jakarta.ws.rs.ProcessingException;
import jakarta.ws.rs.client.ClientRequestContext;
import jakarta.ws.rs.core.MultivaluedHashMap;
import java.net.URI;
import java.time.Instant;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import software.amazon.awssdk.identity.spi.AwsCredentialsIdentity;
import software.amazon.awssdk.identity.spi.AwsSessionCredentialsIdentity;
import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;

class LambdaIamRequestSigningTest {
 private ClientRequestContext request;
 private AwsCredentialsIdentity identity;
 private final LambdaIamRequestSigning provider=new LambdaIamRequestSigning(){
  @Override protected AwsCredentialsIdentity credentials(){return identity;}
 };
 @BeforeEach void setup() throws Exception {
  System.setProperty("csv.iam.region","eu-west-1");
  System.setProperty("csv.iam.endpoints.process-csv-payments-input","https://fixture.lambda-url.eu-west-1.on.aws");
  identity=AwsSessionCredentialsIdentity.create("ASIATEST","fixture-secret","fixture-token");
  request=mock(ClientRequestContext.class);
  var type=Class.forName("org.pipelineframework.csv.service.pipeline.ProcessCsvPaymentsInputRestClient");
  var method=java.util.Arrays.stream(type.getMethods()).filter(m->m.getName().equals("process")).findFirst().orElseThrow();
  when(request.getProperty("org.eclipse.microprofile.rest.client.invokedMethod")).thenReturn(method);
  when(request.getUri()).thenReturn(URI.create("https://fixture.lambda-url.eu-west-1.on.aws/api/v1/csv-payments-input-file/"));
  when(request.getMethod()).thenReturn("POST");when(request.hasEntity()).thenReturn(true);
  when(request.getHeaders()).thenReturn(new MultivaluedHashMap<>());
 }
 @AfterEach void cleanup(){System.clearProperty("csv.iam.region");System.clearProperty("csv.iam.endpoints.process-csv-payments-input");}
 @Test void everySupportedRedirectAliasIsRejected() {
  String full="org.pipelineframework.csv.service.pipeline.ProcessCsvPaymentsInputRestClient";
  for(String property:java.util.List.of(
    "quarkus.rest-client.\""+full+"\".follow-redirects",
    "quarkus.rest-client.ProcessCsvPaymentsInputRestClient.follow-redirects",
    "quarkus.rest-client.\"ProcessCsvPaymentsInputRestClient\".follow-redirects",
    "quarkus.rest-client.process-csv-payments-input.follow-redirects",
    "quarkus.rest-client.\"process-csv-payments-input\".follow-redirects",
    full+"/mp-rest/followRedirects","process-csv-payments-input/mp-rest/followRedirects",
    "quarkus.rest-client.follow-redirects")) {
   System.setProperty(property,"true");
   try {assertThatThrownBy(()->provider.filter(request)).isInstanceOf(ProcessingException.class).hasMessageContaining("disable redirects");}
   finally {System.clearProperty(property);}
  }
 }
 @Test void knownExpiredIdentityIsRejectedBeforeSerialization(){
  identity=software.amazon.awssdk.auth.credentials.AwsSessionCredentials.builder().accessKeyId("ASIATEST").secretAccessKey("fixture-secret")
    .sessionToken("fixture-token").expirationTime(Instant.now().minusSeconds(1)).build();
  assertThatThrownBy(()->provider.filter(request)).isInstanceOf(ProcessingException.class).hasMessageContaining("expired");
  verify(request,never()).setProperty(anyString(),any());
 }
 @Test void nearExpiryIdentityIsRejected(){
  identity=software.amazon.awssdk.auth.credentials.AwsSessionCredentials.builder().accessKeyId("ASIATEST").secretAccessKey("fixture-secret")
    .sessionToken("fixture-token").expirationTime(Instant.now().plusSeconds(10)).build();
  assertThatThrownBy(()->provider.filter(request)).hasMessageContaining("expired");
 }
 @Test void encodedForeignOperationCannotUseBoundOrigin(){
  when(request.getUri()).thenReturn(URI.create("https://fixture.lambda-url.eu-west-1.on.aws/api/v1/csv-payments-input-file/%2e%2e/admin"));
  assertThatThrownBy(()->provider.filter(request)).hasMessageContaining("outside");
 }
 @Test void preexistingAuthenticationCannotBeForwarded(){
  request.getHeaders().add("aUtHoRiZaTiOn","Bearer untrusted");
  assertThatThrownBy(()->provider.filter(request)).hasMessageContaining("Preexisting");
 }
 @Test void missingGeneratedIdentityIsRejected(){
  when(request.getProperty("org.eclipse.microprofile.rest.client.invokedMethod")).thenReturn(null);
  assertThatThrownBy(()->provider.filter(request)).hasMessageContaining("identity");
 }
 @Test void awsServiceHostCannotReplaceFunctionUrl(){
  System.setProperty("csv.iam.endpoints.process-csv-payments-input","https://lambda.eu-west-1.amazonaws.com");
  when(request.getUri()).thenReturn(URI.create("https://lambda.eu-west-1.amazonaws.com/api/v1/csv-payments-input-file/"));
  assertThatThrownBy(()->provider.filter(request)).hasMessageContaining("Function URL");
 }
}
