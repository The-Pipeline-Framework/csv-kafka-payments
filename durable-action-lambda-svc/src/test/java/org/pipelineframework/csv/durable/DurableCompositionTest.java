package org.pipelineframework.csv.durable;

import jakarta.inject.Named;
import org.junit.jupiter.api.Test;
import org.pipelineframework.aws.durable.AwsDurableActionGatewayHandler;
import org.pipelineframework.aws.durable.AwsDurableInputDecoder;
import org.pipelineframework.csv.domain.CsvPaymentsInputFile;
import org.pipelineframework.csv.service.pipeline.ProcessCsvPaymentsInputRestClientStep;
import org.pipelineframework.orchestrator.CanonicalPayloadBinding;
import org.pipelineframework.orchestrator.JsonDurablePayloadCodec;
import org.pipelineframework.config.pipeline.PipelineJson;
import static org.assertj.core.api.Assertions.assertThat;

class DurableCompositionTest {
    @Test void selectsThePublishedHandler() {
        assertThat(AwsDurableActionGatewayHandler.class.getAnnotation(Named.class).value()).isEqualTo("aws-durable-action");
    }

    @Test void decodesTheActualGeneratedRestStepInput() throws Exception {
        // Exercise the emitted class without bypassing deployment preflight to boot a fake host.
        // Real host startup still requires Cloud's complete validated configuration and pinned keys.
        var type = Class.forName("org.pipelineframework.csv.orchestrator.service.AwsDurablePipelineInputDecoder");
        var decoder = (AwsDurableInputDecoder) type.getConstructor().newInstance();
        var mapper = type.getDeclaredField("mapper");
        mapper.setAccessible(true);
        mapper.set(decoder, PipelineJson.mapper());
        var decoded = decoder.decode("{\"filepath\":\"incoming/A.csv\",\"csvFolderPath\":\"incoming\",\"sourceIdentity\":\"object-A\"}");
        assertThat(decoded).isInstanceOf(CsvPaymentsInputFile.class);
        assertThat(ProcessCsvPaymentsInputRestClientStep.class.getMethod("applyOneToMany", decoded.getClass())).isNotNull();
        var canonical = (CsvPaymentsInputFile) decoded;
        assertThat(canonical.sourceIdentity()).isEqualTo("object-A");
        assertThat(canonical.contentRef().isAbsent()).isTrue();
        // The same decoded value must survive the actual release-bound durable codec.
        try (var stream = getClass().getResourceAsStream("/META-INF/pipeline/pipeline-contract.json")) {
            assertThat(stream).as("Actual producer contract is packaged").isNotNull();
            var contract = PipelineJson.mapper().readTree(stream);
            var definition = contract.path("canonicalTypes").path("CsvPaymentsInputFile");
            assertThat(definition.path("runtimeClass").asText()).isEqualTo(CsvPaymentsInputFile.class.getName());
            assertThat(definition.path("definitionFingerprint").asText()).as("Canonical type fingerprint is present").isNotBlank();
            assertThat(contract.path("canonicalCatalogFingerprint").asText()).as("Canonical catalog fingerprint is present").isNotBlank();
            var binding = new CanonicalPayloadBinding("CsvPaymentsInputFile",
                definition.path("definitionFingerprint").asText(),
                contract.path("canonicalCatalogFingerprint").asText(), CsvPaymentsInputFile.class,
                PipelineJson.mapper().getTypeFactory().constructType(CsvPaymentsInputFile.class));
            var codec = new JsonDurablePayloadCodec();
            var restored = (CsvPaymentsInputFile) codec.decode(codec.encode(decoded, binding), binding);
            assertThat(restored).isEqualTo(canonical);
        }
    }

    @Test void firstBurnKeepsAwaitAndOutputWithoutSqlHistorySteps() throws Exception {
        try (var stream = getClass().getResourceAsStream("/META-INF/pipeline/order.json")) {
            assertThat(stream).as("Actual producer execution order is packaged").isNotNull();
            var compiled = PipelineJson.mapper().readTree(stream).path("order");
            assertThat(compiled.isArray()).isTrue();
            var order = new java.util.ArrayList<String>();
            compiled.forEach(step -> order.add(step.asText()));
            assertThat(order).contains(
                "org.pipelineframework.csv.service.pipeline.ProcessCsvPaymentsInputDeferredCompletionStep",
                "org.pipelineframework.csv.service.pipeline.ProcessFinalizePaymentOutputRestClientStep");
            assertThat(order).noneMatch(step -> step.contains("Persistence"));
        }
    }
}
