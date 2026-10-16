package org.pipelineframework.csv.durable;

import jakarta.inject.Named;
import org.junit.jupiter.api.Test;
import org.pipelineframework.aws.durable.AwsDurableActionGatewayHandler;
import org.pipelineframework.aws.durable.AwsDurableInputDecoder;
import org.pipelineframework.csv.dto.CsvPaymentsInputFileDto;
import org.pipelineframework.csv.service.pipeline.ProcessCsvPaymentsInputRestClientStep;
import org.pipelineframework.csv.transport.generated.CsvPaymentsInputFile_02ef56b89614RestMapper;
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
        assertThat(decoded).isInstanceOf(CsvPaymentsInputFileDto.class);
        assertThat(ProcessCsvPaymentsInputRestClientStep.class.getMethod("applyOneToMany", decoded.getClass())).isNotNull();
        var canonical = new CsvPaymentsInputFile_02ef56b89614RestMapper().fromExternal((CsvPaymentsInputFileDto) decoded);
        assertThat(canonical.sourceIdentity()).isEqualTo("object-A");
        assertThat(canonical.contentRef().isAbsent()).isTrue();
    }
}
