package com.exe.morphia3d.config;

import com.exe.morphia3d.service.ai.AiGenerationArtifactStore;
import com.exe.morphia3d.service.ai.AiGenerationProvider;
import com.exe.morphia3d.service.ai.TestOnlyAiGenerationArtifactStore;
import com.exe.morphia3d.service.ai.TestOnlyAiGenerationProvider;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Primary;

@TestConfiguration(proxyBeanMethods = false)
public class TestOnlyAiGenerationConfiguration {
    @Bean(name = "TEST_ONLY_aiGenerationProvider")
    @Primary
    AiGenerationProvider testOnlyAiGenerationProvider() { return new TestOnlyAiGenerationProvider(); }

    @Bean(name = "TEST_ONLY_aiGenerationArtifactStore")
    @Primary
    AiGenerationArtifactStore testOnlyAiGenerationArtifactStore() { return new TestOnlyAiGenerationArtifactStore(); }
}
