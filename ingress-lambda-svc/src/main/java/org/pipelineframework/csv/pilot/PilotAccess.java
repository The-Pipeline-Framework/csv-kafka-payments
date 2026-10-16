package org.pipelineframework.csv.pilot;

import java.security.Principal;
import java.time.Instant;
import java.util.Set;
import io.quarkus.amazon.lambda.http.IAMPrincipal;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.ws.rs.ForbiddenException;
import jakarta.ws.rs.NotAuthorizedException;
import jakarta.ws.rs.ServiceUnavailableException;
import org.eclipse.microprofile.config.ConfigProvider;

/** Restricted invocation policy. Cloud owns and freezes these deployment inputs. */
@ApplicationScoped
public class PilotAccess {
    public record Grant(String tenant, String scope, String principalName) {}

    public boolean enabled() {
        return ConfigProvider.getConfig().getOptionalValue("csv.pilot.enabled", Boolean.class).orElse(false);
    }

    public String required(String name) {
        return ConfigProvider.getConfig().getOptionalValue("csv.pilot." + name, String.class)
            .filter(value -> !value.isBlank())
            .orElseThrow(() -> new ServiceUnavailableException("Pilot deployment binding is incomplete"));
    }

    public Grant authorize(Principal principal, Instant now) {
        if (!enabled()) throw new ServiceUnavailableException("Pilot admission is disabled");
        // Only the native Lambda HTTP identity provider creates this trusted identity.
        // A plain principal or a client-supplied identity header is not sufficient.
        if (!(principal instanceof IAMPrincipal iam) || iam.getIam() == null)
            throw new NotAuthorizedException("AWS IAM authentication is required");
        if (!required("account-id").equals(iam.getIam().getAccountId())
                || !required("principal-arn").equals(iam.getIam().getUserArn())
                || principal.getName() == null || principal.getName().isBlank())
            throw new ForbiddenException("Principal is not invited to this deployment");
        Instant opened;
        Instant closed;
        try {
            opened = Instant.parse(required("opened-at"));
            closed = Instant.parse(required("closes-at"));
        } catch (java.time.format.DateTimeParseException malformed) {
            throw new ServiceUnavailableException("Pilot admission window is invalid");
        }
        // Native reservation lifetime is fifteen minutes. A fixed ten-minute
        // admission window prevents native expiry from allocating another object.
        if (!closed.isAfter(opened) || closed.isAfter(opened.plusSeconds(600)))
            throw new ServiceUnavailableException("Pilot admission window is invalid");
        if (now.isBefore(opened) || !now.isBefore(closed))
            throw new ForbiddenException("Pilot admission window is closed");
        return new Grant(required("tenant"), required("scope"), principal.getName());
    }

    public void requireSlot(String key) {
        if (!Set.of("A", "B").contains(key == null ? "" : key))
            throw new ForbiddenException("This burn test admits only input slots A and B");
    }
}
