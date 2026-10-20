package org.pipelineframework.csv.pilot;

import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.function.Function;
import io.smallrye.config.ConfigSourceContext;
import io.smallrye.config.ConfigSourceFactory;
import org.eclipse.microprofile.config.spi.ConfigSource;
import software.amazon.awssdk.services.ssm.SsmClient;
import software.amazon.awssdk.services.ssm.model.GetParameterRequest;
import software.amazon.awssdk.services.ssm.model.GetParameterResponse;
import software.amazon.awssdk.services.ssm.model.ParameterType;

/** Resolve the ingress payload-signing key from the same immutable native resume generation. */
public final class PilotSigningConfigSourceFactory implements ConfigSourceFactory {
    private static final String PREFIX = "csv.pilot.";
    private static final String SECRET = "pipeline.orchestrator.resume-token-secret";
    private final Function<GetParameterRequest, GetParameterResponse> parameters;

    public PilotSigningConfigSourceFactory() {
        this(request -> {
            try (var client = SsmClient.builder().overrideConfiguration(config -> config
                    .apiCallTimeout(Duration.ofSeconds(15)).apiCallAttemptTimeout(Duration.ofSeconds(5))).build()) {
                return client.getParameter(request);
            }
        });
    }

    PilotSigningConfigSourceFactory(Function<GetParameterRequest, GetParameterResponse> parameters) {
        this.parameters = Objects.requireNonNull(parameters);
    }

    @Override
    public Iterable<ConfigSource> getConfigSources(ConfigSourceContext context) {
        String arn = value(context, PREFIX + "resume-token-parameter-arn");
        String version = value(context, PREFIX + "resume-token-parameter-version");
        String validation = value(context, PREFIX + "signing-lifecycle-validation");
        if (!present(context, PREFIX + "resume-token-parameter-arn")
                && !present(context, PREFIX + "resume-token-parameter-version")
                && !present(context, PREFIX + "signing-lifecycle-validation")) return List.of();
        // Presence, including explicitly blank plaintext input, cannot silently select a fallback.
        if (present(context, SECRET) || present(context, "PIPELINE_ORCHESTRATOR_RESUME_TOKEN_SECRET")) {
            throw new IllegalArgumentException("Mixed CSV ingress signing sources are forbidden");
        }
        if (!arn.matches("arn:[a-z-]+:ssm:[a-z0-9-]+:[0-9]{12}:parameter/tpf/durable/[A-Za-z0-9._-]+/resume-token/g-[a-f0-9]{32,64}")
                || !"1".equals(version) || !"validated".equals(validation)) {
            throw new IllegalArgumentException("CSV ingress requires an immutable validated resume parameter at version 1");
        }
        GetParameterResponse response;
        try {
            response = parameters.apply(GetParameterRequest.builder().name(arn + ":1").withDecryption(true).build());
        } catch (RuntimeException unavailable) {
            // SDK responses and causes may contain key material; retain neither.
            throw new IllegalStateException("CSV ingress signing parameter could not be loaded");
        }
        var parameter = response == null ? null : response.parameter();
        if (parameter == null || !arn.equals(parameter.arn()) || parameter.version() == null || parameter.version() != 1
                || !arn.substring(arn.indexOf(":parameter/") + ":parameter".length()).equals(parameter.name())
                || parameter.type() != ParameterType.SECURE_STRING || parameter.value() == null
                || parameter.value().isBlank() || parameter.value().length() < 32
                || parameter.value().getBytes(StandardCharsets.UTF_8).length > 4096) {
            throw new IllegalStateException("CSV ingress signing response has invalid identity, version, type or size");
        }
        String key = parameter.value();
        return List.of(new ConfigSource() {
            @Override public Map<String, String> getProperties() { return Map.of(SECRET, key); }
            @Override public java.util.Set<String> getPropertyNames() { return java.util.Set.of(SECRET); }
            @Override public String getValue(String name) { return SECRET.equals(name) ? key : null; }
            @Override public int getOrdinal() { return 500; }
            @Override public String getName() { return "csv-ingress-pinned-signing-key"; }
        });
    }

    private boolean present(ConfigSourceContext context, String name) {
        var configured = context.getValue(name);
        return configured != null && configured.getValue() != null;
    }

    private String value(ConfigSourceContext context, String name) {
        var configured = context.getValue(name);
        return configured == null || configured.getValue() == null ? "" : configured.getValue();
    }
}
