package com.exe.morphia3d.service.ai;

import com.exe.morphia3d.config.AiGenerationConfiguration;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;

import static org.assertj.core.api.Assertions.assertThat;

class AiGenerationAdapterConfigurationTest {
    private final ApplicationContextRunner contextRunner = new ApplicationContextRunner()
            .withUserConfiguration(AiGenerationConfiguration.class);

    @Test
    void defaultsToUnavailableProviderAndArtifactStore() {
        contextRunner.run(context -> {
            assertThat(context).hasSingleBean(AiGenerationProvider.class);
            assertThat(context).hasSingleBean(AiGenerationArtifactStore.class);
            assertThat(context.getBean(AiGenerationProvider.class))
                    .isInstanceOf(UnconfiguredAiGenerationProvider.class);
            assertThat(context.getBean(AiGenerationArtifactStore.class))
                    .isInstanceOf(UnconfiguredAiGenerationArtifactStore.class);
        });
    }

    @Test
    void rejectsNonPositiveTimeoutsAndRequestLimits() {
        contextRunner
                .withPropertyValues(
                        "app.ai-generation.provider-timeout-millis=0",
                        "app.ai-generation.requests-per-minute-per-owner=0")
                .run(context -> assertThat(context).hasFailed());
    }
}
