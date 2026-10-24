package org.pipelineframework.csv.iam;

import java.net.URI;
import java.net.http.HttpRequest;
import java.time.Duration;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import software.amazon.awssdk.identity.spi.AwsCredentialsIdentity;
import software.amazon.awssdk.identity.spi.AwsSessionCredentialsIdentity;
import static org.assertj.core.api.Assertions.*;

class LambdaIamHealthRequestSigningTest {
    private AwsCredentialsIdentity identity = AwsSessionCredentialsIdentity.create("ASIATEST", "fixture-secret", "fixture-token");
    private final LambdaIamRequestSigning signer = new LambdaIamRequestSigning() {
        protected AwsCredentialsIdentity credentials() { return identity; }
    };
    private final String key = "process-csv-payments-input";
    private final URI health = URI.create("https://fixture.lambda-url.eu-west-1.on.aws:443/q/health");

    @BeforeEach void configure() {
        System.setProperty("csv.iam.region", "eu-west-1");
        System.setProperty("csv.iam.endpoints." + key, "https://fixture.lambda-url.eu-west-1.on.aws");
    }
    @AfterEach void clear() {
        System.clearProperty("csv.iam.region");
        System.clearProperty("csv.iam.endpoints." + key);
    }
    private HttpRequest request(URI uri) { return HttpRequest.newBuilder(uri).timeout(Duration.ofSeconds(5)).GET().build(); }

    @Test void readinessIsSignedWithSessionCredentialsWithoutChangingTheRequest() {
        var signed = signer.signHealthRequest(key, request(health));
        assertThat(signed.uri()).isEqualTo(health);
        assertThat(signed.method()).isEqualTo("GET");
        assertThat(signed.timeout()).contains(Duration.ofSeconds(5));
        assertThat(signed.bodyPublisher()).isEmpty();
        assertThat(signed.headers().firstValue("Authorization").orElseThrow())
            .contains("AWS4-HMAC-SHA256", "ASIATEST/", "/eu-west-1/lambda/aws4_request");
        assertThat(signed.headers().firstValue("x-amz-security-token")).contains("fixture-token");
        assertThat(signed.headers().firstValue("x-amz-content-sha256"))
            .contains("e3b0c44298fc1c149afbf4c8996fb92427ae41e4649b934ca495991b7852b855");
    }
    @Test void anotherOriginCannotReceiveTheSignature() {
        assertThatThrownBy(() -> signer.signHealthRequest(key, request(URI.create("https://other.lambda-url.eu-west-1.on.aws/q/health"))))
            .hasMessageContaining("outside");
    }
    @Test void livenessCannotSubstituteForReadiness() {
        assertThatThrownBy(() -> signer.signHealthRequest(key, request(URI.create("https://fixture.lambda-url.eu-west-1.on.aws/q/health/live"))))
            .hasMessageContaining("outside");
    }
    @Test void queryOrPostCannotUseTheReadinessPermission() {
        assertThatThrownBy(() -> signer.signHealthRequest(key, request(URI.create(health + "?redirect=elsewhere"))))
            .hasMessageContaining("outside");
        assertThatThrownBy(() -> signer.signHealthRequest(key, HttpRequest.newBuilder(health).POST(HttpRequest.BodyPublishers.noBody()).build()))
            .hasMessageContaining("outside");
    }
    @Test void missingSessionOrExistingAuthenticationFailsClosed() {
        assertThatThrownBy(() -> signer.signHealthRequest(key, HttpRequest.newBuilder(health).header("Authorization", "untrusted").build()))
            .hasMessageContaining("Preexisting");
        identity = software.amazon.awssdk.auth.credentials.AwsBasicCredentials.create("fixture", "fixture");
        assertThatThrownBy(() -> signer.signHealthRequest(key, request(health))).hasMessageContaining("missing or expired");
    }

    @Test void getWithBodyCannotUseTheReadinessPermission() {
        assertThatThrownBy(() -> signer.signHealthRequest(key, HttpRequest.newBuilder(health)
            .method("GET", HttpRequest.BodyPublishers.ofString("unexpected")).build()))
            .hasMessageContaining("outside");
    }
    @Test void existingAwsHeadersCannotBeResigned() {
        assertThatThrownBy(() -> signer.signHealthRequest(key, HttpRequest.newBuilder(health)
            .header("x-amz-date", "20261024T000000Z").build())).hasMessageContaining("Preexisting");
    }
    @Test void userInformationCannotUseTheReadinessPermission() {
        assertThatThrownBy(() -> signer.signHealthRequest(key,
            request(URI.create("https://user@fixture.lambda-url.eu-west-1.on.aws/q/health"))))
            .hasMessageContaining("outside");
    }
    @Test void fragmentCannotUseTheReadinessPermission() {
        assertThatThrownBy(() -> signer.signHealthRequest(key, request(URI.create(health + "#fragment"))))
            .hasMessageContaining("outside");
    }

    @Test void specializationUsesTheSignerForBoundRestHealthOnly() {
        var service = new LambdaIamHealthCheckService() {
            protected LambdaIamRequestSigning signer() { return signer; }
        };
        assertThat(service.customizeHttpHealthRequest("REST", key, request(health)).headers().firstValue("Authorization")).isPresent();
        var grpc = request(URI.create("https://local.example/q/health"));
        assertThat(service.customizeHttpHealthRequest("gRPC", "local", grpc)).isSameAs(grpc);
    }
}
