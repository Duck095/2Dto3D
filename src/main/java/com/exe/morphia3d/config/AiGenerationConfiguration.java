package com.exe.morphia3d.config;

import com.exe.morphia3d.service.ai.AiGenerationArtifactStore;
import com.exe.morphia3d.service.ai.AiGenerationProvider;
import com.exe.morphia3d.service.ai.AiGenerationProperties;
import com.exe.morphia3d.service.ai.UnconfiguredAiGenerationArtifactStore;
import com.exe.morphia3d.service.ai.UnconfiguredAiGenerationProvider;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

@Configuration(proxyBeanMethods = false)
@EnableConfigurationProperties(AiGenerationProperties.class)
public class AiGenerationConfiguration {
    @Bean
    @ConditionalOnMissingBean(AiGenerationProvider.class)
    public AiGenerationProvider unconfiguredAiGenerationProvider() {
        return new UnconfiguredAiGenerationProvider();
    }

    @Bean
    @ConditionalOnMissingBean(AiGenerationArtifactStore.class)
    public AiGenerationArtifactStore unconfiguredAiGenerationArtifactStore() {
        return new UnconfiguredAiGenerationArtifactStore();
    }
}
