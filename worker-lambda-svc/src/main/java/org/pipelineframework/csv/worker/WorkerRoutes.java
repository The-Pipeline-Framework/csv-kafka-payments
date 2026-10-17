package org.pipelineframework.csv.worker;

import jakarta.annotation.Priority;
import jakarta.inject.Inject;
import jakarta.ws.rs.Priorities;
import jakarta.ws.rs.container.ContainerRequestContext;
import jakarta.ws.rs.container.ContainerRequestFilter;
import jakarta.ws.rs.container.PreMatching;
import jakarta.ws.rs.core.Response;
import jakarta.ws.rs.ext.Provider;

/** Only generated operation resources are exposed by this worker package. */
@Provider
@PreMatching
@Priority(Priorities.AUTHORIZATION)
public class WorkerRoutes implements ContainerRequestFilter {
    @Inject WorkerAccess access;

    @Override
    public void filter(ContainerRequestContext request) {
        String path = request.getUriInfo().getPath().replaceFirst("^/", "");
        // UriInfo's default path is decoded. Reject residual encodings as well, so
        // multiple encoding layers cannot conceal traversal from this boundary.
        boolean ambiguous = path.contains("%") || path.contains("\\")
            || java.util.Arrays.stream(path.split("/", -1)).anyMatch(segment -> segment.equals(".") || segment.equals(".."));
        if (!request.getMethod().equals("POST") || !path.startsWith("api/v1/") || ambiguous) {
            request.abortWith(Response.status(403).build());
            return;
        }
        access.authorize(request.getSecurityContext().getUserPrincipal());
    }
}
