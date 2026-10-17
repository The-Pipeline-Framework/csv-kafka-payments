package org.pipelineframework.csv.worker;

import java.security.Principal;
import io.quarkus.amazon.lambda.http.IAMPrincipal;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.ws.rs.ForbiddenException;
import jakarta.ws.rs.NotAuthorizedException;
import jakarta.ws.rs.ServiceUnavailableException;
import org.eclipse.microprofile.config.ConfigProvider;

/** Deployment identity binding, separate from user admission and pipeline context. */
@ApplicationScoped
public class WorkerAccess {
    public void authorize(Principal principal) {
        if (!(principal instanceof IAMPrincipal iam) || iam.getIam() == null)
            throw new NotAuthorizedException("AWS IAM authentication is required");
        String account = required("account-id");
        String caller = required("principal-arn");
        if (!account.equals(iam.getIam().getAccountId()) || !caller.equals(iam.getIam().getUserArn()))
            throw new ForbiddenException("Caller is not bound to this worker deployment");
    }

    private String required(String name) {
        return ConfigProvider.getConfig().getOptionalValue("csv.worker." + name, String.class)
            .filter(value -> !value.isBlank())
            .orElseThrow(() -> new ServiceUnavailableException("Worker deployment binding is incomplete"));
    }
}
