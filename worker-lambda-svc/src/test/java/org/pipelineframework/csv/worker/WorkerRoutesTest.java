package org.pipelineframework.csv.worker;

import jakarta.ws.rs.container.ContainerRequestContext;
import jakarta.ws.rs.core.SecurityContext;
import jakarta.ws.rs.core.UriInfo;
import org.junit.jupiter.api.Test;
import java.security.Principal;
import static org.mockito.Mockito.*;

class WorkerRoutesTest {
    private final WorkerAccess access = mock(WorkerAccess.class);
    private final WorkerRoutes routes = new WorkerRoutes();

    private ContainerRequestContext request(String method, String path) {
        routes.access = access;
        var request = mock(ContainerRequestContext.class);
        var uri = mock(UriInfo.class);
        when(request.getUriInfo()).thenReturn(uri);
        when(request.getMethod()).thenReturn(method);
        when(uri.getPath()).thenReturn(path);
        return request;
    }

    @Test void onlyPostOperationRoutesReachIdentityAuthorization() {
        var request = request("POST", "/api/v1/csv-payments-input-file/page");
        var security = mock(SecurityContext.class);
        Principal caller = () -> "worker";
        when(request.getSecurityContext()).thenReturn(security);
        when(security.getUserPrincipal()).thenReturn(caller);
        routes.filter(request);
        verify(access).authorize(caller);
        verify(request, never()).abortWith(any());
    }

    @Test void controlPlaneAndUnsupportedMethodsAreDeniedBeforeOperationAuthorization() {
        for (String[] route : new String[][] {
                {"POST", "/tpf/executions"}, {"POST", "/tpf/admin/releases"},
                {"GET", "/api/v1/csv-payments-input-file/page"}, {"POST", "/api/v10/page"}}) {
            var request = request(route[0], route[1]);
            routes.filter(request);
            verify(request).abortWith(argThat(response -> response.getStatus() == 403));
        }
        verifyNoInteractions(access);
    }

    @Test void decodedEncodedAndRepeatedlyEncodedTraversalCannotReachAuthorization() {
        for (String path : new String[] {
                "/api/v1/../tpf/executions", "/api/v1/./csv-payments-input-file/page",
                "/api/v1/%2e%2e/tpf/executions", "/api/v1/%252e%252e/tpf/executions",
                "/api/v1/%2f../tpf/executions", "/api/v1/..\\tpf\\executions"}) {
            var request = request("POST", path);
            routes.filter(request);
            verify(request).abortWith(argThat(response -> response.getStatus() == 403));
        }
        verifyNoInteractions(access);
    }
}
