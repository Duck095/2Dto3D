package com.exe.morphia3d.feature;

import com.cloudinary.Cloudinary;
import com.exe.morphia3d.config.CloudinaryConfig;
import com.exe.morphia3d.config.CloudinaryIdentityProperties;
import com.exe.morphia3d.config.MediaStorageConfiguration;
import com.exe.morphia3d.config.QaMediaModeConfiguration;
import com.exe.morphia3d.service.MediaStorageProperties;
import org.junit.jupiter.api.Test;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Import;

import static org.assertj.core.api.Assertions.assertThat;

class QaCloudinaryProxyConfigurationTest {
    private final ApplicationContextRunner runner = new ApplicationContextRunner()
            .withUserConfiguration(MediaConfiguration.class);

    @Test
    void approvedQaUsesExplicitSdkProxyWithoutMakingNetworkCalls() {
        qa().withPropertyValues(liveQa("app.qa-media.proxy-host=qa-egress-proxy",
                        "app.qa-media.proxy-port=3128"))
                .run(context -> {
                    assertThat(context).hasNotFailed();
                    Cloudinary cloudinary = context.getBean(Cloudinary.class);
                    assertThat(cloudinary.config.proxyHost).isEqualTo("qa-egress-proxy");
                    assertThat(cloudinary.config.proxyPort).isEqualTo(3128);
                });
    }

    @Test
    void approvedQaKeepsProxyOptionalWhenNoProxyIsConfigured() {
        qa().withPropertyValues(liveQa())
                .run(context -> {
                    assertThat(context).hasNotFailed();
                    Cloudinary cloudinary = context.getBean(Cloudinary.class);
                    assertThat(cloudinary.config.proxyHost).isNull();
                    assertThat(cloudinary.config.proxyPort).isZero();
                });
    }

    @Test
    void incompleteOrInvalidProxySettingsFailClosed() {
        qa().withPropertyValues(liveQa("app.qa-media.proxy-host=qa-egress-proxy"))
                .run(context -> assertThat(context).hasFailed());
        qa().withPropertyValues(liveQa("app.qa-media.proxy-host=https://proxy.example",
                        "app.qa-media.proxy-port=3128"))
                .run(context -> assertThat(context).hasFailed());
        qa().withPropertyValues(liveQa("app.qa-media.proxy-host=qa-egress-proxy",
                        "app.qa-media.proxy-port=65536"))
                .run(context -> assertThat(context).hasFailed());
    }

    @Test
    void qaDisabledIgnoresProxySettingsAndCreatesNoCloudinaryClient() {
        qa().withPropertyValues("app.qa-media.proxy-host=invalid host",
                        "app.qa-media.proxy-port=99999")
                .run(context -> {
                    assertThat(context).hasNotFailed();
                    assertThat(context).doesNotHaveBean(Cloudinary.class);
                });
    }

    @Test
    void nonQaCloudinaryDoesNotReceiveQaProxySettings() {
        runner.withPropertyValues("cloudinary.cloud-name=local-cloud", "cloudinary.api-key=123456789012",
                        "cloudinary.api-secret=nonfunctional-test-secret")
                .run(context -> {
                    assertThat(context).hasNotFailed();
                    Cloudinary cloudinary = context.getBean(Cloudinary.class);
                    assertThat(cloudinary.config.proxyHost).isNull();
                    assertThat(cloudinary.config.proxyPort).isZero();
                });
    }

    @Test
    void nonQaRejectsQaProxyConfiguration() {
        runner.withPropertyValues("cloudinary.cloud-name=local-cloud", "cloudinary.api-key=123456789012",
                        "cloudinary.api-secret=nonfunctional-test-secret",
                        "app.qa-media.proxy-host=qa-egress-proxy", "app.qa-media.proxy-port=3128")
                .run(context -> assertThat(context).hasFailed());
    }

    private ApplicationContextRunner qa() {
        return runner.withInitializer(context -> context.getEnvironment().setActiveProfiles("qa"));
    }

    private String[] liveQa(String... extra) {
        String[] base = {"app.qa-media.live-enabled=true", "app.qa-media.live-approved=true",
                "app.qa-media.cloud-name=isolated-qa-cloud", "app.qa-media.api-key=123456789012",
                "app.qa-media.api-secret=nonfunctional-test-secret", "cloudinary.cloud-name=isolated-qa-cloud"};
        String[] all = java.util.Arrays.copyOf(base, base.length + extra.length);
        System.arraycopy(extra, 0, all, base.length, extra.length);
        return all;
    }

    @Configuration(proxyBeanMethods = false)
    @EnableConfigurationProperties({CloudinaryIdentityProperties.class, MediaStorageProperties.class})
    @Import({QaMediaModeConfiguration.class, CloudinaryConfig.class, MediaStorageConfiguration.class})
    static class MediaConfiguration {
    }
}
