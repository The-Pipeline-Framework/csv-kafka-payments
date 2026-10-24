package org.pipelineframework.csv.iam;

import jakarta.annotation.Priority;
import jakarta.ws.rs.Path;
import jakarta.ws.rs.ProcessingException;
import jakarta.ws.rs.client.ClientRequestContext;
import jakarta.ws.rs.client.ClientRequestFilter;
import jakarta.ws.rs.client.ClientResponseContext;
import jakarta.ws.rs.client.ClientResponseFilter;
import jakarta.ws.rs.ext.WriterInterceptor;
import jakarta.ws.rs.ext.WriterInterceptorContext;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.OutputStream;
import java.lang.reflect.Method;
import java.net.URI;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import org.eclipse.microprofile.config.ConfigProvider;
import org.eclipse.microprofile.rest.client.inject.RegisterRestClient;
import software.amazon.awssdk.auth.credentials.EnvironmentVariableCredentialsProvider;
import software.amazon.awssdk.http.SdkHttpMethod;
import software.amazon.awssdk.http.SdkHttpRequest;
import software.amazon.awssdk.http.auth.aws.signer.AwsV4HttpSigner;
import software.amazon.awssdk.identity.spi.AwsCredentialsIdentity;
import software.amazon.awssdk.identity.spi.AwsSessionCredentialsIdentity;

/** Opt-in, per-client Lambda IAM signing. No global @Provider registration. */
@Priority(Integer.MAX_VALUE)
public class LambdaIamRequestSigning implements ClientRequestFilter, WriterInterceptor, ClientResponseFilter {
    private static final String STATE = LambdaIamRequestSigning.class.getName() + ".request";
    private static final Map<String, String> ROUTES = Map.of(
            "process-csv-payments-input", "/api/v1/csv-payments-input-file",
            "process-approved-payment-status", "/api/v1/approved-payment-output",
            "process-unapproved-payment-status", "/api/v1/unapproved-payment-output",
            "process-finalize-payment-output", "/api/v1/payment-output");
    private static final int HARD_LIMIT = 6 * 1024 * 1024;

    @Override public void filter(ClientRequestContext request) {
        var config = ConfigProvider.getConfig();
        Object invoked = request.getProperty("org.eclipse.microprofile.rest.client.invokedMethod");
        if (!(invoked instanceof Method method)) throw rejected("Missing generated REST client identity");
        var registration = method.getDeclaringClass().getAnnotation(RegisterRestClient.class);
        if (registration == null || !ROUTES.containsKey(registration.configKey()))
            throw rejected("Unbound REST client");
        String key = registration.configKey();
        URI endpoint = endpoint(config.getValue("csv.iam.endpoints." + key, String.class));
        URI target = request.getUri();
        Path operation = method.getAnnotation(Path.class);
        String expectedPath = ROUTES.get(key) + (operation == null ? "" : operation.value());
        if (!"POST".equals(request.getMethod()) || !sameOrigin(endpoint, target)
                || !expectedPath.equals(target.getRawPath()) || target.getRawFragment() != null
                || target.getRawUserInfo() != null) throw rejected("Target is outside the bound operation");
        // Quarkus 3.39.2 accepts FQCN, simple-name, config-key and MicroProfile
        // aliases. Reject every enabling form, including one shadowed by another
        // setting; checking only the config key can disagree with the built client.
        String fullName = method.getDeclaringClass().getName();
        String simpleName = method.getDeclaringClass().getSimpleName();
        for (String property : List.of(
                "quarkus.rest-client.\"" + fullName + "\".follow-redirects",
                "quarkus.rest-client." + simpleName + ".follow-redirects",
                "quarkus.rest-client.\"" + simpleName + "\".follow-redirects",
                "quarkus.rest-client." + key + ".follow-redirects",
                "quarkus.rest-client.\"" + key + "\".follow-redirects",
                fullName + "/mp-rest/followRedirects",
                key + "/mp-rest/followRedirects",
                "quarkus.rest-client.follow-redirects")) {
            if (config.getOptionalValue(property, Boolean.class).orElse(false))
                throw rejected("IAM clients must disable redirects");
        }
        String region = config.getValue("csv.iam.region", String.class);
        if (!region.matches("[a-z]{2}(?:-[a-z]+)+-[0-9]+")) throw rejected("Invalid signing region");
        if (!endpoint.getHost().matches("[a-z0-9]+\\.lambda-url\\." + java.util.regex.Pattern.quote(region) + "\\.on\\.aws")
                || port(endpoint) != 443) throw rejected("Binding is not a Lambda Function URL in the selected region");
        int limit = config.getOptionalValue("csv.iam.max-body-bytes", Integer.class).orElse(1048576);
        if (limit < 1 || limit > HARD_LIMIT) throw rejected("Invalid signing body limit");
        AwsCredentialsIdentity credentials;
        try { credentials = credentials(); }
        catch (RuntimeException e) { throw rejected("Lambda IAM credentials are unavailable"); }
        if (!(credentials instanceof AwsSessionCredentialsIdentity session) || session.sessionToken().isBlank()
                || credentials.accessKeyId().isBlank() || credentials.secretAccessKey().isBlank()
                || credentials.expirationTime().filter(t -> !t.isAfter(Instant.now().plusSeconds(30))).isPresent())
            throw rejected("Lambda IAM credentials are missing or expired");
        rejectExistingAuthentication(request.getHeaders());
        var state = new RequestState(target, request.getMethod(), region, credentials, limit);
        request.setProperty(STATE, state);
        if (!request.hasEntity()) apply(request.getHeaders(), state, new byte[0]);
    }

    /** Environment resolution performs no network, filesystem or refresh I/O on the event loop. */
    protected AwsCredentialsIdentity credentials() {
        return EnvironmentVariableCredentialsProvider.create().resolveCredentials();
    }

    /** Sign only the bound, read-only readiness endpoint using the same runtime identity. */
    java.net.http.HttpRequest signHealthRequest(String clientKey, java.net.http.HttpRequest request) {
        var config = ConfigProvider.getConfig();
        if (!ROUTES.containsKey(clientKey)) throw rejected("Unbound health client");
        URI bound = endpoint(config.getValue("csv.iam.endpoints." + clientKey, String.class));
        URI target = request.uri();
        String region = config.getValue("csv.iam.region", String.class);
        if (!region.matches("[a-z]{2}(?:-[a-z]+)+-[0-9]+")
                || !bound.getHost().matches("[a-z0-9]+\\.lambda-url\\." + java.util.regex.Pattern.quote(region) + "\\.on\\.aws")
                || port(bound) != 443 || !sameOrigin(bound, target) || !"GET".equals(request.method())
                || !"/q/health".equals(target.getRawPath()) || target.getRawQuery() != null
                || target.getRawFragment() != null || target.getRawUserInfo() != null || request.bodyPublisher().isPresent())
            throw rejected("Health target is outside the bound readiness endpoint");
        AwsCredentialsIdentity identity;
        try { identity = credentials(); }
        catch (RuntimeException unavailable) { throw rejected("Lambda IAM credentials are unavailable"); }
        if (!(identity instanceof AwsSessionCredentialsIdentity session) || session.sessionToken().isBlank()
                || identity.accessKeyId().isBlank() || identity.secretAccessKey().isBlank()
                || identity.expirationTime().filter(t -> !t.isAfter(Instant.now().plusSeconds(30))).isPresent())
            throw rejected("Lambda IAM credentials are missing or expired");
        var headers = new jakarta.ws.rs.core.MultivaluedHashMap<String, Object>();
        request.headers().map().forEach((name, values) -> headers.put(name, new java.util.ArrayList<>(values)));
        apply(headers, new RequestState(target, "GET", region, identity, 0), new byte[0]);
        var result = java.net.http.HttpRequest.newBuilder(request, (name, value) -> false);
        headers.forEach((name, values) -> {
            if (!name.equalsIgnoreCase("host")) values.forEach(value -> result.header(name, value.toString()));
        });
        return result.build();
    }

    @Override public void aroundWriteTo(WriterInterceptorContext context) throws IOException {
        Object value = context.getProperty(STATE);
        if (!(value instanceof RequestState state)) throw rejected("IAM request state is missing");
        OutputStream destination = context.getOutputStream();
        var bytes = new BoundedBody(state.limit(), destination);
        context.setOutputStream(bytes);
        try {
            // Pinned Quarkus snapshots its original in-memory buffer inside proceed(),
            // before returning to us. Tee into that buffer during serialization; copying
            // into it after proceed() would send an empty body. No network send happens yet.
            context.proceed();
            byte[] body = bytes.toByteArray();
            apply(context.getHeaders(), state, body);

        } finally { context.setOutputStream(destination); }
    }

    @Override public void filter(ClientRequestContext request, ClientResponseContext response) {
        if (response.getStatus() >= 300 && response.getStatus() < 400)
            throw rejected("IAM operation returned a forbidden redirect");
    }

    private static void apply(jakarta.ws.rs.core.MultivaluedMap<String, Object> headers,
                              RequestState state, byte[] body) {
        rejectExistingAuthentication(headers);
        var builder = SdkHttpRequest.builder().uri(state.uri()).method(SdkHttpMethod.fromValue(state.method()));
        headers.forEach((name, values) -> {
            if (!name.equalsIgnoreCase("host") && !name.equalsIgnoreCase("content-length")
                    && !name.equalsIgnoreCase("transfer-encoding"))
                builder.putHeader(name, values.stream().map(Object::toString).toList());
        });
        try {
            builder.putHeader("x-amz-content-sha256", java.util.HexFormat.of().formatHex(
                    java.security.MessageDigest.getInstance("SHA-256").digest(body)));
        } catch (java.security.NoSuchAlgorithmException impossible) { throw new IllegalStateException(impossible); }
        var signed = AwsV4HttpSigner.create().sign(r -> r.identity(state.credentials())
                .request(builder.build()).payload(() -> new ByteArrayInputStream(body))
                .putProperty(AwsV4HttpSigner.REGION_NAME, state.region())
                .putProperty(AwsV4HttpSigner.SERVICE_SIGNING_NAME, "lambda")
                .putProperty(AwsV4HttpSigner.PAYLOAD_SIGNING_ENABLED, true)
                .putProperty(AwsV4HttpSigner.CHUNK_ENCODING_ENABLED, false));
        signed.request().headers().forEach((name, values) -> {
            if (name.equalsIgnoreCase("host") || name.equalsIgnoreCase("authorization")
                    || name.toLowerCase(java.util.Locale.ROOT).startsWith("x-amz-")) {
                headers.keySet().removeIf(existing -> existing.equalsIgnoreCase(name));
                headers.put(name, new java.util.ArrayList<>(values));
            }
        });
    }

    private static void rejectExistingAuthentication(Map<String, ? extends List<?>> headers) {
        for (String name : headers.keySet())
            if (name.equalsIgnoreCase("authorization") || name.toLowerCase(java.util.Locale.ROOT).startsWith("x-amz-"))
                throw rejected("Preexisting AWS authentication headers are forbidden");
    }
    private static URI endpoint(String value) {
        URI uri;
        try { uri = URI.create(value); } catch (IllegalArgumentException e) { throw rejected("Invalid IAM endpoint"); }
        if (!"https".equals(uri.getScheme()) || uri.getHost() == null || uri.getRawUserInfo() != null
                || uri.getRawQuery() != null || uri.getRawFragment() != null
                || !(uri.getRawPath().isEmpty() || uri.getRawPath().equals("/")))
            throw rejected("IAM binding must be an exact HTTPS origin");
        return uri;
    }
    private static boolean sameOrigin(URI expected, URI actual) {
        return "https".equals(actual.getScheme()) && expected.getHost().equalsIgnoreCase(actual.getHost())
                && port(expected) == port(actual);
    }
    private static int port(URI uri) { return uri.getPort() == -1 ? 443 : uri.getPort(); }
    private static ProcessingException rejected(String reason) { return new ProcessingException(reason); }
    private record RequestState(URI uri, String method, String region, AwsCredentialsIdentity credentials, int limit) {}
    private static final class BoundedBody extends OutputStream {
        private final int limit;
        private final OutputStream destination;
        private final ByteArrayOutputStream bytes = new ByteArrayOutputStream();
        BoundedBody(int limit, OutputStream destination) { this.limit = limit; this.destination = destination; }
        private void reserve(int length) {
            if (length > limit - bytes.size()) throw rejected("IAM request body exceeds limit");
        }
        @Override public void write(int value) throws IOException {
            reserve(1); bytes.write(value); destination.write(value);
        }
        @Override public void write(byte[] value, int offset, int length) throws IOException {
            reserve(length); bytes.write(value, offset, length); destination.write(value, offset, length);
        }
        @Override public void flush() throws IOException { destination.flush(); }
        @Override public void close() throws IOException { destination.close(); }
        byte[] toByteArray() { return bytes.toByteArray(); }
    }
}
