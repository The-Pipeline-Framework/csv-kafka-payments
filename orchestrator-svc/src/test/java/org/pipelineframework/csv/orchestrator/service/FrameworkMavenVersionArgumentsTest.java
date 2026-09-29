package org.pipelineframework.csv.orchestrator.service;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import java.util.List;
import java.util.Properties;
import org.junit.jupiter.api.Test;

class FrameworkMavenVersionArgumentsTest {

    @Test
    void forwardsOnlyResolvedFrameworkVersionsToNestedBuilds() {
        Properties properties = new Properties();
        properties.setProperty("pipelineframework.bom.version", " 26.9.4-system-test.abc123 ");
        properties.setProperty("pipelineframework.compiler.version", "26.9.4-main.compiler");
        properties.setProperty("pipelineframework.connectors.version", " ");
        properties.setProperty("pipelineframework.runtime.version", "26.9.4-pr.19.runtime");
        properties.setProperty("unrelated.version", "ignored");

        List<String> arguments = FrameworkMavenVersionArguments.from(properties);

        assertEquals(List.of(
                "-Dpipelineframework.bom.version=26.9.4-system-test.abc123",
                "-Dpipelineframework.compiler.version=26.9.4-main.compiler",
                "-Dpipelineframework.runtime.version=26.9.4-pr.19.runtime"), arguments);
        assertThrows(UnsupportedOperationException.class, () -> arguments.add("-Dmutable=true"));
    }
}
