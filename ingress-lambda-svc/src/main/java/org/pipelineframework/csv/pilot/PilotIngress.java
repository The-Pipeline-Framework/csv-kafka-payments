package org.pipelineframework.csv.pilot;

import java.time.Instant;
import java.util.Map;
import java.util.Optional;
import jakarta.inject.Inject;
import jakarta.ws.rs.*;
import jakarta.ws.rs.core.Context;
import jakarta.ws.rs.core.SecurityContext;
import io.smallrye.common.annotation.Blocking;
import org.pipelineframework.config.boundary.PipelineHttpPayloadBoundaryConfig;
import org.pipelineframework.connector.*;
import org.pipelineframework.config.pipeline.PipelineJson;
import org.pipelineframework.config.pipeline.PipelineYamlConfig;
import org.pipelineframework.csv.common.mapper.CsvPaymentFileObjectMapper;
import org.pipelineframework.objectingest.ObjectIdentity;
import org.pipelineframework.objectingest.ObjectSnapshot;
import org.pipelineframework.orchestrator.release.PipelineContractDescriptorLoader;
import org.pipelineframework.repository.PayloadReference;
import org.pipelineframework.aws.durable.model.AwsDurableExecutionInput;
import org.pipelineframework.aws.durable.model.AwsDurableStartResponse;
import software.amazon.awssdk.services.lambda.LambdaClient;

/** Application admission only; native transfer and Durable hosts own all mechanics. */
@jakarta.enterprise.context.ApplicationScoped
@Path("/pilot")
@Blocking
@Consumes("application/json")
@Produces("application/json")
public class PilotIngress {
    @Inject PilotAccess access;
    @Inject ConnectorBindingRegistry bindings;
    @Inject ConnectorRuntimeContext runtimeContext;
    @Inject PilotPayloadAuthorizer authorizer;
    @Inject PipelineContractDescriptorLoader descriptors;
    @Context SecurityContext security;
    private LambdaClient lambda;
    private synchronized LambdaClient client() {
        if (lambda == null) lambda = LambdaClient.create();
        return lambda;
    }
    @jakarta.annotation.PreDestroy
    synchronized void closeClient() {
        if (lambda != null) lambda.close();
    }

    private PipelineYamlConfig config() { return PayloadBoundaryRuntimeConfig.load(); }
    PipelineHttpPayloadBoundaryConfig boundary(PipelineHttpPayloadBoundaryConfig.Direction direction) {
        return new PipelineHttpPayloadBoundaryConfig("csv-input-upload", direction,
            direction == PipelineHttpPayloadBoundaryConfig.Direction.UPLOAD ? "csv-upload-files" : "csv-payment-files",
            "CsvPaymentsInputFile", "contentRef", java.util.List.of("text/csv"),
            direction == PipelineHttpPayloadBoundaryConfig.Direction.UPLOAD ? 1048576L : 0L, "csv.upload");
    }
    private OwnedPayloadTransfer transfer() { return new OwnedPayloadTransfer(bindings, runtimeContext, authorizer); }

    @POST @Path("/uploads")
    public DelegatedPayloadTarget.Reservation initiate(DelegatedUploadInitiation request) {
        var grant = access.authorize(security.getUserPrincipal(), Instant.now());
        if (request == null) throw new BadRequestException();
        access.requireSlot(request.idempotencyKey());
        try {
            return transfer().initiateDelegatedUpload(boundary(PipelineHttpPayloadBoundaryConfig.Direction.UPLOAD),
                config().publish().get("csv-upload-files"), grant.principalName(), grant.tenant(), grant.scope(),
                request.contentType(), request.bytes(), request.sha256(), request.idempotencyKey());
        } catch (PayloadTooLargeException tooLarge) { throw new WebApplicationException(413); }
        catch (PayloadMediaTypeException invalidMedia) { throw new WebApplicationException(415); }
        catch (IllegalArgumentException changedIntent) { throw new BadRequestException(); }
        catch (SecurityException denied) { throw new ForbiddenException(); }
    }

    public record Invocation(PayloadReference contentRef) {}

    @POST @Path("/invocations")
    public AwsDurableStartResponse invoke(Invocation request) throws Exception {
        var grant = access.authorize(security.getUserPrincipal(), Instant.now());
        if (request == null || request.contentRef() == null) throw new BadRequestException();
        var ref = request.contentRef();
        var config = config();
        try {
            // Native open verifies owner, signed provenance and the immutable S3 revision before admission.
            try (var lease = transfer().openDownload(boundary(PipelineHttpPayloadBoundaryConfig.Direction.DOWNLOAD),
                    config.sources().get("csv-payment-files"), grant.principalName(), grant.tenant(), grant.scope(), ref)) {
                // Execution consumes the canonical reference; no transport credential is returned to the client.
            }
        } catch (SecurityException denied) { throw new ForbiddenException(); }
        var snapshot = new ObjectSnapshot("csv-payment-files", ref.provider(), ref.container(), ref.key(), ref.version(),
            ref.checksum(), ref.sizeBytes(), 0L, "text/csv", Map.of(), ref, null, null);
        var input = new CsvPaymentFileObjectMapper().map(snapshot);
        var descriptor = descriptors.load().orElseThrow(() -> new ServiceUnavailableException("Compiled release is unavailable"));
        String qualifier = access.required("ingress-version");
        if (!qualifier.matches("[1-9][0-9]*")) throw new ServiceUnavailableException("Numbered ingress version is required");
        var durable = new AwsDurableExecutionInput(grant.tenant(),
            ObjectIdentity.executionKey("csv-payment-files", snapshot, config.sources().get("csv-payment-files").identity()),
            descriptor.pipelineId(), descriptor.contractVersion(), access.required("release-version"),
            PipelineJson.mapper().writeValueAsString(input), Optional.empty(), 1L);
        {
            var lambda = client();
            // Invoke the existing native ingress as its own pinned host boundary.
            // Combining its custom handlers with Lambda HTTP in one artifact is unsupported.
            var response = lambda.invoke(software.amazon.awssdk.services.lambda.model.InvokeRequest.builder()
                .functionName(access.required("ingress-function")).qualifier(qualifier)
                .invocationType(software.amazon.awssdk.services.lambda.model.InvocationType.REQUEST_RESPONSE)
                .payload(software.amazon.awssdk.core.SdkBytes.fromUtf8String(PipelineJson.mapper().writeValueAsString(durable)))
                .build());
            if (response.statusCode() != 200 || response.functionError() != null)
                throw new WebApplicationException("Native Durable ingress failed", 502);
            var receipt = PipelineJson.mapper().readValue(response.payload().asUtf8String(), AwsDurableStartResponse.class);
            if (receipt.statusCode() != 202 || !org.pipelineframework.aws.durable.model.AwsDurableExecutionNames
                    .durableExecutionName(grant.tenant(), durable.idempotencyKey(), durable.generation())
                    .equals(receipt.durableExecutionName()))
                throw new WebApplicationException("Native Durable ingress returned an invalid receipt", 502);
            return receipt; // Provider acceptance is not a terminal result or a Cloud ACTIVE claim.
        }
    }
}
