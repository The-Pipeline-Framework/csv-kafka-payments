package org.pipelineframework.csv.worker;

import java.security.Principal;
import java.util.HashMap;
import java.util.Map;
import java.util.regex.Pattern;
import io.quarkus.amazon.lambda.http.IAMPrincipal;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.ws.rs.ForbiddenException;
import jakarta.ws.rs.NotAuthorizedException;
import jakarta.ws.rs.ServiceUnavailableException;
import org.eclipse.microprofile.config.Config;
import jakarta.inject.Inject;

/** Deployment identity binding, separate from user admission and pipeline context. */
@ApplicationScoped
public class WorkerAccess {
    private final Config config;
    @Inject public WorkerAccess(Config config) { this.config = config; }
    private static final String ROLE_PREFIX = "csv.worker.allowed-role-arns.";
    private static final Pattern ROLE = Pattern.compile("arn:([a-z0-9-]+):iam::([0-9]{12}):role/([A-Za-z0-9_+=,.@/-]+)");
    private static final Pattern SESSION = Pattern.compile("arn:([a-z0-9-]+):sts::([0-9]{12}):assumed-role/([A-Za-z0-9_+=,.@-]{1,64})/([A-Za-z0-9_+=,.@-]{2,64})");

    public void authorize(Principal principal) {
        if (!(principal instanceof IAMPrincipal iam) || iam.getIam() == null)
            throw new NotAuthorizedException("AWS IAM authentication is required");
        String account = required("account-id");
        String caller = config.getOptionalValue("csv.worker.principal-arn", String.class)
            .filter(value -> !value.isBlank()).orElse(null);
        Map<String, String> roles = roleBindings(account);
        if (!account.matches("[0-9]{12}") || (caller == null) == roles.isEmpty())
            throw incomplete(); // Exactly one binding mode; never add privileges through mixed modes.
        boolean allowed = account.equals(iam.getIam().getAccountId());
        if (caller != null) allowed &= caller.equals(iam.getIam().getUserArn());
        else allowed &= roleMatches(roles, iam.getIam().getUserArn(), iam.getIam().getUserId());
        if (!allowed) throw new ForbiddenException("Caller is not bound to this worker deployment");
    }

    private Map<String, String> roleBindings(String account) {
        Map<String, String> roles = new HashMap<>();
        for (String property : config.getPropertyNames()) {
            if (!property.startsWith(ROLE_PREFIX)) continue;
            String id = property.substring(ROLE_PREFIX.length());
            String arn = config.getOptionalValue(property, String.class).orElse("");
            var match = ROLE.matcher(arn);
            if (!id.matches("[A-Za-z0-9]{1,128}") || !match.matches() || !account.equals(match.group(2)))
                throw incomplete();
            String path = match.group(3);
            String name = path.substring(path.lastIndexOf('/') + 1);
            if (!name.matches("[A-Za-z0-9_+=,.@-]{1,64}") || path.contains("//") || roles.containsValue(arn))
                throw incomplete();
            roles.put(id, arn);
        }
        return Map.copyOf(roles);
    }

    private boolean roleMatches(Map<String, String> roles, String arn, String userId) {
        if (arn == null || userId == null) return false;
        var session = SESSION.matcher(arn);
        if (!session.matches()) return false;
        for (var entry : roles.entrySet()) {
            var role = ROLE.matcher(entry.getValue());
            if (!role.matches()) return false;
            String path = role.group(3);
            String name = path.substring(path.lastIndexOf('/') + 1);
            if (role.group(1).equals(session.group(1)) && role.group(2).equals(session.group(2))
                    && name.equals(session.group(3)) && (entry.getKey() + ":" + session.group(4)).equals(userId))
                return true;
        }
        return false;
    }

    private ServiceUnavailableException incomplete() {
        return new ServiceUnavailableException("Worker deployment binding is incomplete");
    }

    private String required(String name) {
        return config.getOptionalValue("csv.worker." + name, String.class)
            .filter(value -> !value.isBlank())
            .orElseThrow(() -> new ServiceUnavailableException("Worker deployment binding is incomplete"));
    }
}
