package org.pipelineframework.csv.pilot;

import org.junit.jupiter.api.Test;
import org.pipelineframework.config.boundary.PipelineHttpPayloadBoundaryConfig.Direction;
import org.pipelineframework.config.pipeline.PipelineJson;
import static org.assertj.core.api.Assertions.*;

class PilotIngressContractTest {
    @Test void uploadPolicyMatchesTheActualCompiledBoundary() throws Exception {
        try (var stream = getClass().getResourceAsStream("/META-INF/pipeline/pipeline-contract.json")) {
            assertThat(stream).as("Producer's unchanged Compiled Truth is packaged").isNotNull();
            var boundaries = PipelineJson.mapper().readTree(stream).path("httpPayloadBoundaries");
            assertThat(boundaries.size()).isEqualTo(1);
            var compiled = boundaries.get(0);
            var policy = new PilotIngress().boundary(Direction.UPLOAD);
            assertThat(policy.name()).isEqualTo(compiled.path("name").asText());
            assertThat(policy.direction().name()).isEqualTo(compiled.path("direction").asText());
            assertThat(policy.objectName()).isEqualTo(compiled.path("object").asText());
            assertThat(policy.canonicalType()).isEqualTo(compiled.path("canonicalType").asText());
            assertThat(policy.referenceField()).isEqualTo(compiled.path("referenceField").asText());
            assertThat(policy.maxBytes()).isEqualTo(compiled.path("maxBytes").asLong());
            assertThat(policy.authorizationScope()).isEqualTo(compiled.path("authorizationScope").asText());
            var contentTypes = PipelineJson.mapper().<com.fasterxml.jackson.databind.JsonNode>valueToTree(policy.contentTypes());
            assertThat(contentTypes).isEqualTo(compiled.path("contentTypes"));
        }
    }
}
