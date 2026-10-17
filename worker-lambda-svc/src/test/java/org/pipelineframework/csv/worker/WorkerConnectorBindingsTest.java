package org.pipelineframework.csv.worker;

import java.util.concurrent.CompletableFuture;
import org.junit.jupiter.api.Test;
import org.pipelineframework.connector.ConnectorBindingRegistry;
import org.pipelineframework.connector.ConnectorRuntimeContext;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.*;

class WorkerConnectorBindingsTest {
    private final ConnectorBindingRegistry registry = mock(ConnectorBindingRegistry.class);
    private final ConnectorRuntimeContext context = mock(ConnectorRuntimeContext.class);
    private final WorkerConnectorBindings filter = new WorkerConnectorBindings();

    private WorkerConnectorBindings configured() {
        filter.bindings = registry;
        filter.runtimeContext = context;
        return filter;
    }

    @Test void activatesDeclaredBindingsInOrderWithNativeRuntimeContext() {
        var inputReady = new CompletableFuture<Void>();
        when(registry.activate(WorkerConnectorBindings.INPUT, context)).thenReturn(inputReady);
        when(registry.activate(WorkerConnectorBindings.OUTPUT, context)).thenReturn(CompletableFuture.completedFuture(null));
        var activation = configured().activate();
        verifyNoInteractions(registry);
        var completion = activation.subscribeAsCompletionStage();
        verify(registry).activate(WorkerConnectorBindings.INPUT, context);
        verify(registry, never()).activate(WorkerConnectorBindings.OUTPUT, context);
        assertThat(completion.toCompletableFuture()).isNotDone();
        inputReady.complete(null);
        completion.toCompletableFuture().join();
        var order = inOrder(registry);
        order.verify(registry).activate(WorkerConnectorBindings.INPUT, context);
        order.verify(registry).activate(WorkerConnectorBindings.OUTPUT, context);
    }

    @Test void failedInputActivationFailsClosedBeforeOutputActivation() {
        var failure = new IllegalStateException("deployment credentials unavailable");
        when(registry.activate(WorkerConnectorBindings.INPUT, context)).thenReturn(CompletableFuture.failedFuture(failure));
        assertThatThrownBy(() -> configured().activate().await().indefinitely()).isSameAs(failure);
        verify(registry, never()).activate(WorkerConnectorBindings.OUTPUT, context);
    }

    @Test void failedOutputActivationCannotAdmitTheOperation() {
        var failure = new IllegalStateException("output binding unavailable");
        when(registry.activate(WorkerConnectorBindings.INPUT, context)).thenReturn(CompletableFuture.completedFuture(null));
        when(registry.activate(WorkerConnectorBindings.OUTPUT, context)).thenReturn(CompletableFuture.failedFuture(failure));
        assertThatThrownBy(() -> configured().activate().await().indefinitely()).isSameAs(failure);
    }
}
