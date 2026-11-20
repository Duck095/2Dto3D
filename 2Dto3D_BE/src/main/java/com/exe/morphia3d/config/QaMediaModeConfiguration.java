package com.exe.morphia3d.config;

import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.env.Environment;

@Configuration(proxyBeanMethods = false)
@EnableConfigurationProperties(QaMediaActivationProperties.class)
public class QaMediaModeConfiguration {
    @Bean
    public QaMediaActivation qaMediaActivation(Environment environment) {
        return QaMediaActivation.resolve(environment);
    }
}
