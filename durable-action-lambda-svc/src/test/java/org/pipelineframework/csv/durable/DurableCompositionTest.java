package org.pipelineframework.csv.durable;

import jakarta.inject.Named;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfSystemProperty;
import org.junit.jupiter.api.io.TempDir;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.concurrent.TimeUnit;
import java.util.zip.ZipFile;
import org.pipelineframework.aws.durable.AwsDurableActionGatewayHandler;
import org.pipelineframework.aws.durable.AwsDurableInputDecoder;
import org.pipelineframework.csv.domain.CsvPaymentsInputFile;
import org.pipelineframework.csv.service.pipeline.ProcessCsvPaymentsInputRestClientStep;
import org.pipelineframework.orchestrator.CanonicalPayloadBinding;
import org.pipelineframework.orchestrator.JsonDurablePayloadCodec;
import org.pipelineframework.config.pipeline.PipelineJson;
import static org.assertj.core.api.Assertions.assertThat;

class DurableCompositionTest {
    @Test
    @EnabledIfSystemProperty(named = "csv.action.package.path", matches = ".+")
    void initializesTheActualPackagedLambdaConfiguration(@TempDir Path extracted) throws Exception {
        var archive = Path.of(System.getProperty("csv.action.package.path")).toAbsolutePath();
        assertThat(archive).isRegularFile();
        try (var zip = new ZipFile(archive.toFile())) {
            for (var entry : zip.stream().toList()) {
                var destination = extracted.resolve(entry.getName()).normalize();
                assertThat(destination.startsWith(extracted)).isTrue();
                if (entry.isDirectory()) Files.createDirectories(destination);
                else {
                    Files.createDirectories(destination.getParent());
                    try (var input = zip.getInputStream(entry)) { Files.copy(input, destination); }
                }
            }
        }
        // The client classifier remains the sole packaged authority for all eight role classes.
        try (var libraries = Files.list(extracted.resolve("lib"))) {
            var jars = libraries.filter(path -> path.toString().endsWith(".jar")).toList();
            for (var step : new String[]{"CsvPaymentsInput", "ApprovedPaymentStatus",
                    "UnapprovedPaymentStatus", "FinalizePaymentOutput"}) {
                for (var suffix : new String[]{"", "Step"}) {
                    var resource = "org/pipelineframework/csv/service/pipeline/Process"
                        + step + "RestClient" + suffix + ".class";
                    assertThat(extracted.resolve(resource)).as(resource).doesNotExist();
                    var definitions = new java.util.ArrayList<Path>();
                    for (var jar : jars) {
                        try (var zip = new ZipFile(jar.toFile())) {
                            if (zip.getEntry(resource) != null) definitions.add(jar);
                        }
                    }
                    assertThat(definitions).as(resource).hasSize(1);
                    assertThat(definitions.getFirst().getFileName().toString()).contains("orchestrator-client");
                }
            }
        }
        var testClasses = Path.of(getClass().getProtectionDomain().getCodeSource().getLocation().toURI());
        var classpath = extracted + java.io.File.pathSeparator + extracted.resolve("lib/*")
            + java.io.File.pathSeparator + testClasses;
        var log = archive.getParent().resolve("packaged-lambda-startup.log");
        var builder = new ProcessBuilder(Path.of(System.getProperty("java.home"), "bin", "java").toString(),
            "-cp", classpath, PackagedLambdaStartup.class.getName())
            .directory(extracted.toFile()).redirectErrorStream(true).redirectOutput(log.toFile());
        // No inherited credentials, signing material, runtime configuration or workstation TLS.
        var environment = builder.environment();
        environment.clear();
        environment.put("AWS_ACCESS_KEY_ID", "offline-startup");
        environment.put("AWS_SECRET_ACCESS_KEY", "offline-startup");
        environment.put("AWS_REGION", "us-east-2");
        environment.put("AWS_EC2_METADATA_DISABLED", "true");
        environment.put("AWS_SHARED_CREDENTIALS_FILE", "/dev/null");
        environment.put("AWS_CONFIG_FILE", "/dev/null");
        environment.put("AWS_ENDPOINT_URL", "http://127.0.0.1:1");
        environment.put("AWS_LAMBDA_RUNTIME_API", "127.0.0.1:1");
        var process = builder.start();
        try {
            assertThat(process.waitFor(45, TimeUnit.SECONDS)).as("Packaged startup completed; see %s", log).isTrue();
            var output = Files.readString(log);
            assertThat(process.exitValue()).as(output).isZero();
            assertThat(output).contains("Profile prod activated", "PACKAGED_LAMBDA_CONFIGURATION_STARTED");
        } finally { process.destroyForcibly(); }
    }

    public static class PackagedLambdaStartup {
        public static void main(String[] arguments) throws Exception {
            // These are the published QuarkusStreamHandler's exact initialization calls.
            // No request is dispatched: Cloud deployment preflight and business readiness remain separate gates.
            Class.forName("io.quarkus.amazon.lambda.runtime.QuarkusStreamHandler").getConstructor().newInstance();
            Class.forName("io.quarkus.runtime.Quarkus").getMethod("manualStart").invoke(null);
            System.out.println("PACKAGED_LAMBDA_CONFIGURATION_STARTED");
            // End this isolated configuration-only JVM before any Lambda request dispatch.
            System.exit(0);
        }
    }

    @Test void selectsThePublishedHandler() {
        assertThat(AwsDurableActionGatewayHandler.class.getAnnotation(Named.class).value()).isEqualTo("aws-durable-action");
    }

    @Test void decodesTheActualGeneratedRestStepInput() throws Exception {
        // Exercise the emitted class without bypassing deployment preflight to boot a fake host.
        // Real request dispatch still requires Cloud's complete validated configuration and pinned keys.
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
