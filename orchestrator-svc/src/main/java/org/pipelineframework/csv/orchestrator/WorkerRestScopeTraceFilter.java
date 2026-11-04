package org.pipelineframework.csv.orchestrator;

import java.util.UUID;

import io.quarkus.arc.Arc;
import io.quarkus.vertx.http.runtime.CurrentVertxRequest;
import io.vertx.ext.web.RoutingContext;
import jakarta.annotation.PreDestroy;
import jakarta.enterprise.context.RequestScoped;
import jakarta.inject.Inject;
import jakarta.ws.rs.ConstrainedTo;
import jakarta.ws.rs.RuntimeType;
import jakarta.ws.rs.container.ContainerRequestContext;
import jakarta.ws.rs.container.ContainerRequestFilter;
import jakarta.ws.rs.ext.Provider;
import org.eclipse.microprofile.config.inject.ConfigProperty;
import org.jboss.logging.Logger;
import org.pipelineframework.orchestrator.RestTransitionWorkerProtocol;

/** Draft-only diagnostic for the REST transition request's own CDI scope. */
@Provider
@ConstrainedTo(RuntimeType.SERVER)
public class WorkerRestScopeTraceFilter implements ContainerRequestFilter {
    private static final Logger LOG = Logger.getLogger(WorkerRestScopeTraceFilter.class);

    @ConfigProperty(name = "csv.ha.worker-scope-trace", defaultValue = "false")
    boolean enabled;

    @Inject
    CurrentVertxRequest currentRequest;

    @Inject
    ScopeProbe probe;

    @Override
    public void filter(ContainerRequestContext request) {
        if (enabled && "POST".equals(request.getMethod())
            && RestTransitionWorkerProtocol.EXECUTE_PATH.equals(request.getUriInfo().getRequestUri().getPath())) {
            probe.arm(currentRequest.getCurrent());
        }
    }

    @RequestScoped
    public static class ScopeProbe {
        private String traceId;
        private String stateId;

        void arm(RoutingContext request) {
            String trace = UUID.randomUUID().toString();
            String state = Integer.toHexString(Arc.container().requestContext().getState().hashCode());
            traceId = trace;
            stateId = state;
            LOG.infof("event=worker_rest_scope_open traceId=%s stateId=%s", trace, state);
            request.addEndHandler(outcome -> LOG.infof(
                "event=worker_rest_scope_end traceId=%s stateId=%s status=%d endSucceeded=%s",
                trace, state, request.response().getStatusCode(), outcome.succeeded()));
        }

        @PreDestroy
        void captureDestruction() {
            if (traceId != null) {
                LOG.infof(new Throwable("REST worker CDI request-state destruction"),
                    "event=worker_rest_scope_destroy traceId=%s stateId=%s", traceId, stateId);
            }
        }
    }
}
