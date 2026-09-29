package org.pipelineframework.csv.orchestrator.service;

import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.Properties;

final class FrameworkMavenVersionArguments {

    private static final List<String> PROPERTY_NAMES = List.of(
            "pipelineframework.bom.version",
            "pipelineframework.compiler.version",
            "pipelineframework.connectors.version",
            "pipelineframework.runtime.version");

    private FrameworkMavenVersionArguments() {}

    static List<String> from(Properties properties) {
        Objects.requireNonNull(properties, "properties");
        return PROPERTY_NAMES.stream()
                .flatMap(name -> Optional.ofNullable(properties.getProperty(name))
                        .map(String::trim)
                        .filter(value -> !value.isBlank())
                        .map(value -> "-D" + name + "=" + value)
                        .stream())
                .toList();
    }
}
