package org.pipelineframework.csv.pilot;

import java.time.Instant;
import java.util.Map;
import java.util.Optional;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import static org.assertj.core.api.Assertions.*;
import com.amazonaws.services.lambda.runtime.events.APIGatewayV2HTTPEvent;
import io.quarkus.amazon.lambda.http.IAMPrincipal;
import jakarta.ws.rs.ForbiddenException;
import jakarta.ws.rs.NotAuthorizedException;
import jakarta.ws.rs.ServiceUnavailableException;
import org.pipelineframework.connector.PayloadBoundaryAuthorizationRequest;
import static org.mockito.Mockito.*;

class PilotAccessTest {
    private final PilotAccess access = new PilotAccess();
    private final Instant now = Instant.parse("2026-10-15T22:00:01Z");
    private final Map<String,String> properties = Map.of(
        "enabled", "true", "account-id", "000000000000", "principal-arn", "arn:aws:iam::000000000000:user/invited",
        "tenant", "pilot-tenant", "scope", "pilot-scope", "opened-at", "2026-10-15T22:00:00Z", "closes-at", "2026-10-15T22:10:00Z");
    private final Map<String,String> previous = new java.util.HashMap<>();
    @BeforeEach void configure() { properties.forEach((k,v) -> { previous.put(k,System.getProperty("csv.pilot."+k));System.setProperty("csv.pilot."+k,v); }); }
    @AfterEach void restore() { properties.keySet().forEach(k -> { if(previous.get(k)==null)System.clearProperty("csv.pilot."+k);else System.setProperty("csv.pilot."+k,previous.get(k)); }); }
    private IAMPrincipal iam(String account, String arn) {
        var identity = new APIGatewayV2HTTPEvent.RequestContext.IAM();
        identity.setAccountId(account); identity.setUserArn(arn); identity.setUserId("invited-user-id");
        return new IAMPrincipal(identity);
    }
    private IAMPrincipal invited() { return iam(properties.get("account-id"),properties.get("principal-arn")); }
    @Test void authenticIamIdentityGetsOnlyServerOwnedTenantAndScope() {
        var grant=access.authorize(invited(),now);
        assertThat(grant.tenant()).isEqualTo("pilot-tenant"); assertThat(grant.scope()).isEqualTo("pilot-scope");
    }
    @Test void missingIdentityIsNotAuthenticated() { assertThatThrownBy(() -> access.authorize(null,now)).isInstanceOf(NotAuthorizedException.class); }
    @Test void plainPrincipalLookingLikeInvitedIamCannotAuthenticate() { assertThatThrownBy(() -> access.authorize(() -> "invited-user-id",now)).isInstanceOf(NotAuthorizedException.class); }
    @Test void foreignAccountCannotUseMatchingName() { assertThatThrownBy(() -> access.authorize(iam("111111111111",properties.get("principal-arn")),now)).isInstanceOf(ForbiddenException.class); }
    @Test void uninvitedArnCannotUseMatchingAccount() { assertThatThrownBy(() -> access.authorize(iam(properties.get("account-id"),"arn:aws:iam::000000000000:user/other"),now)).isInstanceOf(ForbiddenException.class); }
    @Test void closedWindowRejectsAtTheExactBoundary() { assertThatThrownBy(() -> access.authorize(invited(),Instant.parse(properties.get("closes-at")))).isInstanceOf(ForbiddenException.class); }
    @Test void notYetOpenWindowRejects() { assertThatThrownBy(() -> access.authorize(invited(),now.minusSeconds(2))).isInstanceOf(ForbiddenException.class); }
    @Test void windowCannotOutliveNativeReservations() { System.setProperty("csv.pilot.closes-at","2026-10-15T22:15:00Z"); assertThatThrownBy(() -> access.authorize(invited(),now)).isInstanceOf(ServiceUnavailableException.class); }
    @Test void malformedWindowIsAnUnavailableBinding() { System.setProperty("csv.pilot.opened-at","yesterday"); assertThatThrownBy(() -> access.authorize(invited(),now)).isInstanceOf(ServiceUnavailableException.class); }
    @Test void disabledPilotRejectsEvenInvitedIam() { System.setProperty("csv.pilot.enabled","false"); assertThatThrownBy(() -> access.authorize(invited(),now)).isInstanceOf(ServiceUnavailableException.class); }
    @Test void onlyTheTwoPreassignedSlotsAreAdmitted() { access.requireSlot("A");access.requireSlot("B"); for(String key:new String[]{"C","a","../A"," A",""}) assertThatThrownBy(() -> access.requireSlot(key)).isInstanceOf(ForbiddenException.class); }
    @Test void generatedTransferCannotTrustEchoedOwnerHeaders() {
        var authorizer=new PilotPayloadAuthorizer();authorizer.access=mock(PilotAccess.class);authorizer.security=mock(io.quarkus.security.identity.SecurityIdentity.class);
        when(authorizer.access.authorize(any(),any())).thenReturn(new PilotAccess.Grant("pilot-tenant","pilot-scope","invited-user-id"));
        var allowed = new PayloadBoundaryAuthorizationRequest(PayloadBoundaryAuthorizationRequest.Action.UPLOAD,"csv-input-upload","csv.upload","invited-user-id","pilot-tenant","pilot-scope",Optional.empty());
        assertThat(authorizer.authorize(allowed)).isEqualTo(new org.pipelineframework.connector.PayloadBoundaryOwner("pilot-tenant","pilot-scope"));
        for(var request: java.util.List.of(
            new PayloadBoundaryAuthorizationRequest(PayloadBoundaryAuthorizationRequest.Action.UPLOAD,"csv-input-upload","csv.upload","invited-user-id","other","pilot-scope",Optional.empty()),
            new PayloadBoundaryAuthorizationRequest(PayloadBoundaryAuthorizationRequest.Action.UPLOAD,"csv-input-upload","csv.upload","invited-user-id","pilot-tenant","wrong-scope",Optional.empty()),
            new PayloadBoundaryAuthorizationRequest(PayloadBoundaryAuthorizationRequest.Action.UPLOAD,"csv-input-upload","csv.upload","other-user","pilot-tenant","pilot-scope",Optional.empty()),
            new PayloadBoundaryAuthorizationRequest(PayloadBoundaryAuthorizationRequest.Action.UPLOAD,"other-boundary","csv.upload","invited-user-id","pilot-tenant","pilot-scope",Optional.empty()),
            new PayloadBoundaryAuthorizationRequest(PayloadBoundaryAuthorizationRequest.Action.UPLOAD,"csv-input-upload","other.scope","invited-user-id","pilot-tenant","pilot-scope",Optional.empty()))) {
            assertThatThrownBy(() -> authorizer.authorize(request)).isInstanceOf(SecurityException.class);
        }
    }
}
