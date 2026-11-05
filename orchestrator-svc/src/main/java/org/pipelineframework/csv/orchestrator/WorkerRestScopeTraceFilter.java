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

/** Opt-in diagnostic for the REST transition request's own CDI scope. */
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
        private FailureTrace trace;

        void arm(RoutingContext request) {
            var requestTrace = new FailureTrace(UUID.randomUUID().toString(),
                Integer.toHexString(Arc.container().requestContext().getState().hashCode()));
            trace = requestTrace;
            request.addEndHandler(outcome -> requestTrace.responseEnded(request.response().getStatusCode()));
        }

        @PreDestroy
        void captureDestruction() {
            if (trace != null) {
                trace.destroyed();
            }
        }
    }

    // Response completion and CDI teardown can run on different threads and in either order.
    private static final class FailureTrace {
        private final String traceId;
        private final String stateId;
        private int status;
        private Throwable destruction;

        private FailureTrace(String traceId, String stateId) {
            this.traceId = traceId;
            this.stateId = stateId;
        }

        synchronized void responseEnded(int responseStatus) {
            status = responseStatus;
            if (status < 500) {
                destruction = null;
            }
            logFailure();
        }

        synchronized void destroyed() {
            if (status == 0 || status >= 500) {
                destruction = new Throwable("REST worker CDI request-state destruction");
                logFailure();
            }
        }

        private void logFailure() {
            if (status >= 500 && destruction != null) {
                LOG.warnf(destruction, "event=worker_rest_scope_5xx traceId=%s stateId=%s status=%d",
                    traceId, stateId, status);
                destruction = null;
            }
        }
    }
}
