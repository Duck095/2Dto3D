package com.exe.morphia3d.service;

import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Import;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;

class MediaAssetReconcilerConditionTest {

    private final ApplicationContextRunner contextRunner = new ApplicationContextRunner()
            .withUserConfiguration(ReconcilerTestConfiguration.class)
            .withInitializer(context -> {
                context.getBeanFactory().registerSingleton(
                        "mediaAssetLifecycleService", mock(MediaAssetLifecycleService.class));
                context.getBeanFactory().registerSingleton(
                        "mediaStorageService", mock(MediaStorageService.class));
            });

    @Test
    void reconciliationIsEnabledWhenPropertyIsMissing() {
        contextRunner.run(context -> {
            assertThat(context.getStartupFailure()).isNull();
            assertThat(context).hasSingleBean(MediaAssetReconciler.class);
        });
    }

    @Test
    void reconciliationIsEnabledWhenPropertyIsTrue() {
        contextRunner.withPropertyValues("media.lifecycle.reconciliation-enabled=true")
                .run(context -> {
                    assertThat(context.getStartupFailure()).isNull();
                    assertThat(context).hasSingleBean(MediaAssetReconciler.class);
                });
    }

    @Test
    void reconciliationBeanIsAbsentWhenPropertyIsFalse() {
        contextRunner.withPropertyValues("media.lifecycle.reconciliation-enabled=false")
                .run(context -> {
                    assertThat(context.getStartupFailure()).isNull();
                    assertThat(context).doesNotHaveBean(MediaAssetReconciler.class);
                });
    }

    @Configuration(proxyBeanMethods = false)
    @Import(MediaAssetReconciler.class)
    static class ReconcilerTestConfiguration {
    }
}
