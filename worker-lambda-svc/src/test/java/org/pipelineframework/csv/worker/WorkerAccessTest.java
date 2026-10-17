package org.pipelineframework.csv.worker;

import com.amazonaws.services.lambda.runtime.events.APIGatewayV2HTTPEvent;
import io.quarkus.amazon.lambda.http.IAMPrincipal;
import jakarta.ws.rs.ForbiddenException;
import jakarta.ws.rs.NotAuthorizedException;
import jakarta.ws.rs.ServiceUnavailableException;
import org.junit.jupiter.api.*;
import static org.assertj.core.api.Assertions.*;

class WorkerAccessTest {
    private final WorkerAccess access = new WorkerAccess();
    private String oldAccount, oldCaller;
    private static final String ACCOUNT = "000000000000";
    private static final String CALLER = "arn:aws:iam::000000000000:role/local-worker-fixture";
    @BeforeEach void configure() {
        oldAccount = System.getProperty("csv.worker.account-id");
        oldCaller = System.getProperty("csv.worker.principal-arn");
        System.setProperty("csv.worker.account-id", ACCOUNT);
        System.setProperty("csv.worker.principal-arn", CALLER);
    }
    @AfterEach void restore() {
        restore("csv.worker.account-id", oldAccount);
        restore("csv.worker.principal-arn", oldCaller);
    }
    private static void restore(String key, String value) {
        if (value == null) System.clearProperty(key); else System.setProperty(key, value);
    }
    private IAMPrincipal iam(String account, String caller) {
        var identity = new APIGatewayV2HTTPEvent.RequestContext.IAM();
        identity.setAccountId(account); identity.setUserArn(caller); identity.setUserId("worker-session");
        return new IAMPrincipal(identity);
    }
    @Test void boundIamCallerIsAccepted() { access.authorize(iam(ACCOUNT, CALLER)); }
    @Test void anonymousAndPlainPrincipalsCannotAuthenticate() {
        assertThatThrownBy(() -> access.authorize(null)).isInstanceOf(NotAuthorizedException.class);
        assertThatThrownBy(() -> access.authorize(() -> CALLER)).isInstanceOf(NotAuthorizedException.class);
    }
    @Test void foreignAccountCannotReuseCaller() {
        assertThatThrownBy(() -> access.authorize(iam("111111111111", CALLER))).isInstanceOf(ForbiddenException.class);
    }
    @Test void foreignCallerCannotReuseAccount() {
        assertThatThrownBy(() -> access.authorize(iam(ACCOUNT, CALLER + "-other"))).isInstanceOf(ForbiddenException.class);
    }
    @Test void missingDeploymentBindingFailsClosed() {
        System.setProperty("csv.worker.principal-arn", "");
        assertThatThrownBy(() -> access.authorize(iam(ACCOUNT, CALLER))).isInstanceOf(ServiceUnavailableException.class);
    }
}
