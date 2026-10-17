package org.pipelineframework.csv.worker;

import io.smallrye.mutiny.Uni;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import jakarta.ws.rs.Priorities;
import org.jboss.resteasy.reactive.server.ServerRequestFilter;
import org.pipelineframework.connector.ConnectorBindingName;
import org.pipelineframework.connector.ConnectorBindingRegistry;
import org.pipelineframework.connector.ConnectorRuntimeContext;

/** Activates the worker's declared S3 bindings through the native provider lifecycle. */
@ApplicationScoped
public class WorkerConnectorBindings {
    static final ConnectorBindingName INPUT = ConnectorBindingName.of("csv-input-s3");
    static final ConnectorBindingName OUTPUT = ConnectorBindingName.of("csv-output-s3");

    @Inject ConnectorBindingRegistry bindings;
    @Inject ConnectorRuntimeContext runtimeContext;

    // Post-match activation follows the pre-match route and IAM checks in WorkerRoutes.
    // The registry owns activation idempotency, provider lifetime and shutdown.
    @ServerRequestFilter(priority = Priorities.AUTHORIZATION + 1)
    public Uni<Void> activate() {
        return Uni.createFrom().completionStage(() -> bindings.activate(INPUT, runtimeContext))
            .chain(() -> Uni.createFrom().completionStage(() -> bindings.activate(OUTPUT, runtimeContext)));
    }
}
