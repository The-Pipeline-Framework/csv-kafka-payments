package org.pipelineframework.csv.pilot;

import jakarta.inject.Inject;
import jakarta.ws.rs.container.ContainerRequestContext;
import jakarta.ws.rs.container.ContainerRequestFilter;
import jakarta.ws.rs.core.Response;
import jakarta.ws.rs.ext.Provider;

/** Fail closed for direct native initiation and privileged APIs on the pilot HTTP surface. */
@Provider
@jakarta.ws.rs.container.PreMatching
public class PilotRoutes implements ContainerRequestFilter {
    @Inject PilotAccess access;

    @Override
    public void filter(ContainerRequestContext request) {
        String path = request.getUriInfo().getPath().replaceFirst("^/", "");
        boolean pilotRoute = path.equals("pilot/uploads") || path.equals("pilot/invocations");
        if (!access.enabled()) {
            request.abortWith(Response.status(pilotRoute ? 503 : 403).build());
            return;
        }
        boolean transfer = path.matches("tpf/payloads/csv-input-upload/upload/(part|complete|abort)");
        if (!(request.getMethod().equals("POST") && (pilotRoute || transfer)))
            request.abortWith(Response.status(403).build());
    }
}
