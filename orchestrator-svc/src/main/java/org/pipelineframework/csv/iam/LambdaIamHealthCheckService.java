package org.pipelineframework.csv.iam;

import jakarta.enterprise.context.ApplicationScoped;
import jakarta.enterprise.inject.Alternative;
import jakarta.annotation.Priority;
import java.net.http.HttpRequest;
import org.eclipse.microprofile.config.ConfigProvider;
import org.pipelineframework.HealthCheckService;

/** Authenticates configured Lambda dependency probes without bypassing their readiness result. */
@Alternative
@Priority(1)
@ApplicationScoped
public class LambdaIamHealthCheckService extends HealthCheckService {
    // Binary-compatible with the earlier runtime; the coordinated runtime candidate adds this hook.
    // The combined compatibility gate must prove superclass dispatch, not just this method in isolation.
    protected HttpRequest customizeHttpHealthRequest(String type, String name, HttpRequest request) {
        if (!"REST".equals(type) || ConfigProvider.getConfig()
                .getOptionalValue("csv.iam.endpoints." + name, String.class).isEmpty()) return request;
        return signer().signHealthRequest(name, request);
    }

    protected LambdaIamRequestSigning signer() { return new LambdaIamRequestSigning(); }
}
