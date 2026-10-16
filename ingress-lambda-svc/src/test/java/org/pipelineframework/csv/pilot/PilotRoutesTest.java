package org.pipelineframework.csv.pilot;

import org.junit.jupiter.api.Test;
import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;
import jakarta.ws.rs.container.ContainerRequestContext;
import jakarta.ws.rs.core.UriInfo;
import jakarta.ws.rs.core.Response;
import org.mockito.ArgumentCaptor;

class PilotRoutesTest {
 private ContainerRequestContext request(String method,String path) {
  var request=mock(ContainerRequestContext.class);var uri=mock(UriInfo.class);
  when(request.getUriInfo()).thenReturn(uri);when(uri.getPath()).thenReturn(path);when(request.getMethod()).thenReturn(method);return request;
 }
 private PilotRoutes routes(boolean enabled) {var r=new PilotRoutes();r.access=mock(PilotAccess.class);when(r.access.enabled()).thenReturn(enabled);return r;}
 @Test void disabledAdmissionCannotFallThroughToEntityDecoding() {
  for(var path:new String[]{"pilot/uploads","/pilot/uploads","/pilot/invocations"}) {
   var req=request("POST",path);routes(false).filter(req);var response=ArgumentCaptor.forClass(Response.class);verify(req).abortWith(response.capture());assertThat(response.getValue().getStatus()).isEqualTo(503);
  }
 }
 @Test void validTransferAndPilotPathsReachTheirNativeAuthorization() {
  for(var path:new String[]{"/pilot/uploads","pilot/invocations","/tpf/payloads/csv-input-upload/upload/complete"}) {var req=request("POST",path);routes(true).filter(req);verify(req,never()).abortWith(any());}
 }
 @Test void disabledPilotExposesNoOtherHttpSurface() {
  for(var path:new String[]{"/tpf/admin/tenants/pilot-tenant/pipelines/x/releases/register","/tpf/executions","/pilot/uploads/","/tpf/payloads/csv-input-upload/upload/part"}) {
   var req=request("POST",path);routes(false).filter(req);var response=ArgumentCaptor.forClass(Response.class);verify(req).abortWith(response.capture());assertThat(response.getValue().getStatus()).isEqualTo(403);
  }
 }
 @Test void rawInitiationAndPrivilegedOrUnlistedMethodsCannotBypassAdmission() {
  for(var path:new String[]{"/tpf/payloads/csv-input-upload/upload/initiate","/tpf/admin/tenants/other/releases","/pilot/uploads/","/pilot/../uploads"}) {
   var req=request("POST",path);routes(true).filter(req);var response=ArgumentCaptor.forClass(Response.class);verify(req).abortWith(response.capture());assertThat(response.getValue().getStatus()).isEqualTo(403);
  }
  var req=request("GET","/pilot/uploads");routes(true).filter(req);verify(req).abortWith(any());
 }
}
