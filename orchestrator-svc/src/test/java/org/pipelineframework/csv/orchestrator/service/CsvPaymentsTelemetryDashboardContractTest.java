/*
 * Copyright (c) 2026 Mariano Barcia
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

import static org.junit.jupiter.api.Assertions.assertTrue;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import org.junit.jupiter.api.Test;

class CsvPaymentsTelemetryDashboardContractTest {
    private static final Path GRAFANA = Path.of("src", "main", "resources", "META-INF", "grafana", "grafana-dashboard-csv-payments.json");
    private static final Path TEMPO = Path.of("src", "main", "resources", "META-INF", "grafana", "grafana-dashboard-csv-payments-tempo.json");

    @Test
    void dashboardQueriesProveCanonicalCsvJourneyObligations() throws Exception {
        JsonNode metrics = new ObjectMapper().readTree(Files.readString(GRAFANA));
        JsonNode tempo = new ObjectMapper().readTree(Files.readString(TEMPO));
        List<String> metricQueries = values(metrics, "expr");
        List<String> traceQueries = values(tempo, "query");

        for (ObservabilityObligations.Obligation obligation : ObservabilityObligations.CSV_PAYMENTS_JOURNEY) {
            for (String metric : obligation.requiredMetricNames()) {
                assertTrue(metricQueries.stream().anyMatch(query -> query.contains(metric)),
                        () -> obligation.transition() + " lacks a metrics query for " + metric);
            }
            for (String span : obligation.requiredTraceSpanNames()) {
                assertTrue(traceQueries.stream().anyMatch(query -> query.contains(span)),
                        () -> obligation.transition() + " lacks a Tempo query for " + span);
            }
        }
    }

    @Test
    void tempoProofDeclaresProfileAndRootlessAwaitDiagnostic() throws Exception {
        JsonNode tempo = new ObjectMapper().readTree(Files.readString(TEMPO));
        List<String> traceQueries = values(tempo, "query");
        assertTrue(Files.readString(TEMPO).contains("telemetry-capable CSV Payments developer profile"));
        assertTrue(Files.readString(TEMPO).contains("self-host container profile, which intentionally disables telemetry"));
        assertTrue(traceQueries.stream().anyMatch(query -> query.contains("tpf.await.origin.linked") && query.contains("false")),
                "Tempo dashboard must expose rootless/unlinked Await completion spans");
        assertTrue(traceQueries.stream().anyMatch(query -> query.contains("tpf.await.origin.linked") && query.contains("nil")),
                "Tempo dashboard must expose Await completion spans missing origin-link metadata");
    }

    @Test
    void operatorDashboardPreservesOperationalPanelsWithoutHighCardinalityDimensions() throws Exception {
        JsonNode metrics = new ObjectMapper().readTree(Files.readString(GRAFANA));
        List<String> metricQueries = values(metrics, "expr");
        Set<String> panelTitles = Set.copyOf(values(metrics, "title"));

        for (ObservabilityObligations.OperatorPanel panel : ObservabilityObligations.CSV_PAYMENTS_OPERATOR_PANELS) {
            assertTrue(panelTitles.contains(panel.title()), "Missing operator panel: " + panel.title());
            for (String metric : panel.requiredMetricNames()) {
                assertTrue(metricQueries.stream().anyMatch(query -> query.contains(metric)),
                        () -> panel.title() + " lacks a query for " + metric);
            }
        }
        assertTrue(metricQueries.stream().noneMatch(query -> query.contains("tpfProof")),
                "The dashboard proof marker is target metadata, not PromQL.");
        for (String forbidden : List.of("execution_id", "interaction_id", "correlation_id", "request_id", "item_index")) {
            assertTrue(metricQueries.stream().noneMatch(query -> query.contains(forbidden)),
                    () -> "Metric dashboard must not use high-cardinality dimension " + forbidden);
        }
    }

    private static List<String> values(JsonNode node, String field) {
        List<String> result = new ArrayList<>();
        collect(node, field, result);
        return List.copyOf(result);
    }

    private static void collect(JsonNode node, String field, List<String> result) {
        if (node.isObject()) {
            node.fields().forEachRemaining(entry -> {
                if (field.equals(entry.getKey()) && entry.getValue().isTextual()) {
                    result.add(entry.getValue().asText());
                }
                collect(entry.getValue(), field, result);
            });
        } else if (node.isArray()) {
            node.forEach(value -> collect(value, field, result));
        }
    }
}
