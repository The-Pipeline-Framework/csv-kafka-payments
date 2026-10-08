/*
 * Copyright (c) 2023-2025 Mariano Barcia
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package org.pipelineframework.csv.orchestrator.service;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.jar.JarFile;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;

class PipelineOrderMetadataIT {

    @Test
    void generatedOrderRemainsResolvableForTerminalQueueSegments() {
        List<String> order;
        Path generatedOrder = Path.of("target", "classes", "META-INF", "pipeline", "order.json");
        try (InputStream stream = Files.newInputStream(generatedOrder)) {
            order = readOrder(stream);
        } catch (IOException exception) {
            throw new AssertionError("Could not read the generated pipeline order", exception);
        }

        assertEquals(7, order.size(), "Queue terminal resolution requires the complete generated order");
        assertTrue(order.getLast().endsWith("PersistencePaymentOutputSideEffectGrpcClientStep"),
                "The generated terminal step must remain available to the coordinator");
        assertPackagedOrderResolvesAllSteps(order);
    }

    private static void assertPackagedOrderResolvesAllSteps(List<String> order) {
        Path applicationDirectory = Path.of("target", "quarkus-app", "app");
        try (var applicationFiles = Files.list(applicationDirectory)) {
            Path packagedApplication = applicationFiles
                    .filter(path -> path.getFileName().toString().startsWith("orchestrator-svc-")
                            && path.getFileName().toString().endsWith(".jar"))
                    .findFirst()
                    .orElseThrow(() -> new AssertionError("Packaged coordinator application jar is missing"));
            try (JarFile application = new JarFile(packagedApplication.toFile())) {
                var orderEntry = Optional.ofNullable(application.getEntry("META-INF/pipeline/order.json"))
                        .orElseThrow(() -> new AssertionError(
                                "The packaged coordinator must contain pipeline order metadata"));
                try (var orderStream = application.getInputStream(orderEntry)) {
                    assertEquals(order, readOrder(orderStream),
                            "Packaged pipeline order must preserve the complete resolved step sequence");
                }
                for (String step : order) {
                    String entryName = step.replace('.', '/') + ".class";
                    assertTrue(application.getEntry(entryName) != null,
                            () -> "The packaged coordinator is missing generated step " + entryName);
                }
            }
        } catch (IOException exception) {
            throw new AssertionError("Could not inspect the packaged coordinator application", exception);
        }
    }

    private static List<String> readOrder(InputStream stream) throws IOException {
        JsonNode orderNode = new ObjectMapper().readTree(stream).path("order");
        assertTrue(orderNode.isArray(), "Pipeline order must be an array");
        List<String> order = new ArrayList<>();
        for (JsonNode step : orderNode) {
            assertTrue(step.isTextual(), "Every pipeline step name must be text");
            order.add(step.textValue());
        }
        return order;
    }
}
