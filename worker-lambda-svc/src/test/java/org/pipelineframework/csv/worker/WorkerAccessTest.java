package org.pipelineframework.csv.worker;

import com.amazonaws.services.lambda.runtime.events.APIGatewayV2HTTPEvent;
import io.quarkus.amazon.lambda.http.IAMPrincipal;
import jakarta.ws.rs.ForbiddenException;
import jakarta.ws.rs.NotAuthorizedException;
import jakarta.ws.rs.ServiceUnavailableException;
import org.junit.jupiter.api.*;
import static org.assertj.core.api.Assertions.*;

class WorkerAccessTest {
    private final WorkerAccess access = new WorkerAccess(config());
    private static org.eclipse.microprofile.config.Config config() {
        var config = org.mockito.Mockito.mock(org.eclipse.microprofile.config.Config.class);
        org.mockito.Mockito.when(config.getPropertyNames()).thenAnswer(call -> System.getProperties().stringPropertyNames());
        org.mockito.Mockito.when(config.getOptionalValue(org.mockito.ArgumentMatchers.anyString(), org.mockito.ArgumentMatchers.eq(String.class)))
            .thenAnswer(call -> java.util.Optional.ofNullable(System.getProperty(call.getArgument(0))));
        return config;
    }
    private String oldAccount, oldCaller;
    private static final String ACCOUNT = "000000000000";
    private static final String CALLER = "arn:aws:iam::000000000000:role/local-worker-fixture";
    private static final String ROLE_ID = "AROATESTIMMUTABLEACTION";
    private static final String WORKER_ID = "AROATESTIMMUTABLEWORKER";
    private static final String ROLE_PREFIX = "csv.worker.allowed-role-arns.";
    @BeforeEach void configure() {
        oldAccount = System.getProperty("csv.worker.account-id");
        oldCaller = System.getProperty("csv.worker.principal-arn");
        System.setProperty("csv.worker.account-id", ACCOUNT);
        System.setProperty("csv.worker.principal-arn", CALLER);
    }
    @AfterEach void restore() {
        restore("csv.worker.account-id", oldAccount);
        restore("csv.worker.principal-arn", oldCaller);
        System.clearProperty(ROLE_PREFIX + ROLE_ID);
        System.clearProperty(ROLE_PREFIX + WORKER_ID);
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
    private void roleBindings() {
        System.clearProperty("csv.worker.principal-arn");
        System.setProperty(ROLE_PREFIX + ROLE_ID, "arn:aws:iam::" + ACCOUNT + ":role/native/action-role");
        System.setProperty(ROLE_PREFIX + WORKER_ID, "arn:aws:iam::" + ACCOUNT + ":role/native/worker-role");
    }
    private IAMPrincipal session(String account, String role, String session, String id) {
        IAMPrincipal principal = iam(account, "arn:aws:sts::" + account + ":assumed-role/" + role + "/" + session);
        principal.getIam().setUserId(id + ":" + session);
        return principal;
    }
    @Test void bothNativeRolesAllowDifferentLegitimateSessions() {
        roleBindings();
        for (String session : new String[]{"cold-start-1", "resumed-start-2"}) {
            access.authorize(session(ACCOUNT, "action-role", session, ROLE_ID));
            access.authorize(session(ACCOUNT, "worker-role", session, WORKER_ID));
        }
    }
    @Test void roleIdentityAccountNameAndSessionMustAllMatch() {
        roleBindings();
        for (IAMPrincipal caller : new IAMPrincipal[]{
                session("111111111111", "action-role", "session-1", ROLE_ID),
                session(ACCOUNT, "foreign-role", "session-1", ROLE_ID),
                session(ACCOUNT, "action-role", "session-1", WORKER_ID),
                session(ACCOUNT, "action-role", "session-1", ROLE_ID + "EXTRA"),
                iam(ACCOUNT, "arn:aws:iam::" + ACCOUNT + ":root"),
                iam(ACCOUNT, "arn:aws:iam::" + ACCOUNT + ":role/native/action-role")})
            assertThatThrownBy(() -> access.authorize(caller)).isInstanceOf(ForbiddenException.class);
        var wrongSession = session(ACCOUNT, "action-role", "session-1", ROLE_ID);
        wrongSession.getIam().setUserId(ROLE_ID + ":session-2");
        assertThatThrownBy(() -> access.authorize(wrongSession)).isInstanceOf(ForbiddenException.class);
        wrongSession.getIam().setUserId(null);
        assertThatThrownBy(() -> access.authorize(wrongSession)).isInstanceOf(ForbiddenException.class);
    }
    @Test void malformedRoleConfigurationFailsClosed() {
        roleBindings();
        for (String value : new String[]{"*", "arn:aws:iam::111111111111:role/action-role", "arn:aws:iam::" + ACCOUNT + ":role/action-role/*"}) {
            System.setProperty(ROLE_PREFIX + ROLE_ID, value);
            assertThatThrownBy(() -> access.authorize(session(ACCOUNT, "action-role", "session-1", ROLE_ID)))
                .isInstanceOf(ServiceUnavailableException.class);
        }
    }
    @Test void mixedLegacyAndRoleModesFailClosed() {
        roleBindings();
        System.setProperty("csv.worker.principal-arn", CALLER);
        assertThatThrownBy(() -> access.authorize(iam(ACCOUNT, CALLER))).isInstanceOf(ServiceUnavailableException.class);
    }
}
