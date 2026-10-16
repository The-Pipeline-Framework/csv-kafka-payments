package org.pipelineframework.csv.pilot;

import java.time.Instant;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import io.quarkus.security.identity.SecurityIdentity;
import org.pipelineframework.connector.PayloadBoundaryAuthorizer;
import org.pipelineframework.connector.PayloadBoundaryAuthorizationRequest;
import org.pipelineframework.connector.PayloadBoundaryOwner;

/** Applies the same server-owned identity to generated part/complete/abort routes. */
@ApplicationScoped
public class PilotPayloadAuthorizer implements PayloadBoundaryAuthorizer {
    @Inject PilotAccess access;
    @Inject SecurityIdentity security;

    @Override
    public PayloadBoundaryOwner authorize(PayloadBoundaryAuthorizationRequest request) {
        var grant = access.authorize(security.getPrincipal(), Instant.now());
        if (!grant.principalName().equals(request.principalName())
                || !grant.tenant().equals(request.requestedTenantId())
                || !grant.scope().equals(request.requestedScopeId())
                || !"csv-input-upload".equals(request.boundaryName())
                || !"csv.upload".equals(request.authorizationScope()))
            throw new SecurityException("Payload owner scope is not authorized");
        return new PayloadBoundaryOwner(grant.tenant(), grant.scope());
    }
}
