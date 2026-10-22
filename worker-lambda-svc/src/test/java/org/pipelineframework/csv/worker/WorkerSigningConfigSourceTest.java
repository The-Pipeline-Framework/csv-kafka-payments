package org.pipelineframework.csv.worker;

import io.smallrye.config.ConfigSourceFactory;
import java.util.ServiceLoader;
import org.junit.jupiter.api.Test;
import org.pipelineframework.csv.pilot.PilotSigningConfigSourceFactory;
import static org.assertj.core.api.Assertions.assertThat;

class WorkerSigningConfigSourceTest {
    @Test void workerDiscoversTheSharedStrictSigningFactoryExactlyOnce() {
        assertThat(ServiceLoader.load(ConfigSourceFactory.class).stream()
            .map(ServiceLoader.Provider::type)
            .filter(PilotSigningConfigSourceFactory.class::equals).count()).isEqualTo(1);
    }
}
