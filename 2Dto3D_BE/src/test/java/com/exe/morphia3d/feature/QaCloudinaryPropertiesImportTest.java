package com.exe.morphia3d.feature;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.boot.SpringApplication;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.env.Environment;

import java.nio.file.Files;
import java.nio.file.Path;

import static org.assertj.core.api.Assertions.assertThat;

class QaCloudinaryPropertiesImportTest {
    @TempDir
    Path temporaryDirectory;

    @Test
    void springPropertiesImportResolvesSyntheticQaCredentialBindings() throws Exception {
        Path syntheticProperties = temporaryDirectory.resolve("cloudinary-qa.properties");
        Files.writeString(syntheticProperties, String.join("\n",
                "QA_CLOUDINARY_CLOUD_NAME=synthetic-qa-cloud",
                "QA_CLOUDINARY_API_KEY=synthetic-key-123456",
                "QA_CLOUDINARY_API_SECRET=synthetic-secret-value-for-import-test",
                "APP_QA_MEDIA_LIVE_ENABLED=false",
                "QA_MEDIA_LIVE_APPROVED=false",
                "QA_CLOUDINARY_PROXY_HOST=synthetic-proxy",
                "QA_CLOUDINARY_PROXY_PORT=3128"));

        try (var context = new SpringApplication(ImportProbeConfiguration.class)
                .run("--spring.main.web-application-type=none", "--spring.profiles.active=qa",
                        "--spring.config.import=file:" + syntheticProperties.toAbsolutePath())) {
            Environment environment = context.getEnvironment();
            assertThat(environment.getProperty("app.qa-media.live-enabled")).isEqualTo("false");
            assertThat(environment.getProperty("app.qa-media.live-approved")).isEqualTo("false");
            assertThat(environment.getProperty("app.qa-media.cloud-name")).isEqualTo("synthetic-qa-cloud");
            assertThat(environment.getProperty("app.qa-media.api-key")).isEqualTo("synthetic-key-123456");
            assertThat(environment.getProperty("app.qa-media.api-secret"))
                    .isEqualTo("synthetic-secret-value-for-import-test");
            assertThat(environment.getProperty("app.qa-media.proxy-host")).isEqualTo("synthetic-proxy");
            assertThat(environment.getProperty("app.qa-media.proxy-port")).isEqualTo("3128");
            assertThat(environment.getProperty("QA_CLOUDINARY_CLOUD_NAME")).isEqualTo("synthetic-qa-cloud");
            assertThat(environment.getProperty("QA_CLOUDINARY_API_KEY")).isEqualTo("synthetic-key-123456");
            assertThat(environment.getProperty("QA_CLOUDINARY_API_SECRET"))
                    .isEqualTo("synthetic-secret-value-for-import-test");
            assertThat(environment.getProperty("APP_QA_MEDIA_LIVE_ENABLED")).isEqualTo("false");
            assertThat(environment.getProperty("QA_MEDIA_LIVE_APPROVED")).isEqualTo("false");
            assertThat(environment.getProperty("QA_CLOUDINARY_PROXY_HOST")).isEqualTo("synthetic-proxy");
            assertThat(environment.getProperty("QA_CLOUDINARY_PROXY_PORT")).isEqualTo("3128");
        }
    }

    @Configuration(proxyBeanMethods = false)
    static class ImportProbeConfiguration {
    }
}
