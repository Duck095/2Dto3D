package com.exe.morphia3d.feature;

import com.cloudinary.Cloudinary;
import com.exe.morphia3d.config.CloudinaryConfig;
import com.exe.morphia3d.config.CloudinaryIdentityProperties;
import com.exe.morphia3d.config.MediaStorageConfiguration;
import com.exe.morphia3d.config.QaMediaModeConfiguration;
import com.exe.morphia3d.config.QaMediaActivation;
import com.exe.morphia3d.service.MediaStorageProperties;
import com.exe.morphia3d.service.DeleteResult;
import com.exe.morphia3d.service.MediaAssetIdentity;
import com.exe.morphia3d.service.MediaStorageException;
import com.exe.morphia3d.service.MediaStorageService;
import com.exe.morphia3d.service.MediaUploadIntent;
import com.exe.morphia3d.service.MediaUploadResult;
import com.exe.morphia3d.service.ValidatedMedia;
import com.exe.morphia3d.service.impl.CloudinaryMediaStorageService;
import com.exe.morphia3d.service.impl.QaDisabledMediaStorageService;
import org.junit.jupiter.api.Test;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Import;
import org.springframework.context.annotation.Bean;
import org.springframework.core.env.MapPropertySource;
import org.springframework.core.env.PropertiesPropertySource;
import org.springframework.mock.env.MockEnvironment;

import static org.assertj.core.api.Assertions.assertThat;

class QaMediaActivationConfigurationTest {
    private final ApplicationContextRunner runner = new ApplicationContextRunner()
            .withUserConfiguration(MediaConfiguration.class);

    @Test
    void defaultQaCreatesOnlyFailClosedStorageAndNoCloudinaryClient() {
        qa().run(context -> {
            assertThat(context).hasNotFailed();
            assertThat(context).hasSingleBean(MediaStorageService.class);
            assertThat(context.getBean(MediaStorageService.class)).isInstanceOf(QaDisabledMediaStorageService.class);
            assertThat(context).doesNotHaveBean(Cloudinary.class);
            assertThat(context).doesNotHaveBean(CloudinaryMediaStorageService.class);
        });
    }

    @Test
    void approvedQaCreatesOnlyGenuineCloudinaryStorageWithDedicatedIdentity() {
        qa().withPropertyValues("app.qa-media.live-enabled=true", "app.qa-media.live-approved=true",
                        "app.qa-media.cloud-name=isolated-qa-cloud", "app.qa-media.api-key=123456789012",
                        "app.qa-media.api-secret=nonfunctional-test-secret", "cloudinary.cloud-name=isolated-qa-cloud")
                .run(context -> {
                    assertThat(context).hasNotFailed();
                    assertThat(context).hasSingleBean(MediaStorageService.class);
                    assertThat(context.getBean(MediaStorageService.class)).isInstanceOf(CloudinaryMediaStorageService.class);
                    assertThat(((CloudinaryMediaStorageService) context.getBean(MediaStorageService.class)).folderPolicy())
                            .isEqualTo(CloudinaryMediaStorageService.FolderPolicy.QA_DYNAMIC_PRODUCT_MEDIA);
                    assertThat(context).hasSingleBean(Cloudinary.class);
                    assertThat(context.getBean(Cloudinary.class).config.cloudName).isEqualTo("isolated-qa-cloud");
                    assertThat(context).doesNotHaveBean(QaDisabledMediaStorageService.class);
                });
    }

    @Test
    void nonQaKeepsExistingCloudinarySelection() {
        runner.withPropertyValues("cloudinary.cloud-name=local-cloud", "cloudinary.api-key=123456789012",
                        "cloudinary.api-secret=nonfunctional-test-secret")
                .run(context -> {
                    assertThat(context).hasNotFailed();
                    assertThat(context).hasSingleBean(MediaStorageService.class);
                    assertThat(context.getBean(MediaStorageService.class)).isInstanceOf(CloudinaryMediaStorageService.class);
                    assertThat(((CloudinaryMediaStorageService) context.getBean(MediaStorageService.class)).folderPolicy())
                            .isEqualTo(CloudinaryMediaStorageService.FolderPolicy.DEFAULT);
                    assertThat(context).hasSingleBean(Cloudinary.class);
                });
    }

    @Test
    void nonQaCloudinaryFailsClosedWhenApiCredentialsAreBlank() {
        runner.withPropertyValues("cloudinary.cloud-name=local-cloud", "cloudinary.api-key= ",
                        "cloudinary.api-secret=nonfunctional-test-secret")
                .run(context -> {
                    assertThat(context).hasFailed();
                    assertThat(context.getStartupFailure())
                            .hasStackTraceContaining("Cloudinary API key is required for real media storage");
                });
    }

    @Test
    void localConfigurationSelectsScopedProductMediaFolders() {
        runner.withPropertyValues("cloudinary.cloud-name=local-cloud", "cloudinary.api-key=123456789012",
                        "cloudinary.api-secret=nonfunctional-test-secret",
                        "media.storage.cloudinary-folder-policy=local-product-media")
                .run(context -> {
                    assertThat(context).hasNotFailed();
                    assertThat(((CloudinaryMediaStorageService) context.getBean(MediaStorageService.class))
                            .folderPolicy())
                            .isEqualTo(CloudinaryMediaStorageService.FolderPolicy.LOCAL_PRODUCT_MEDIA);
                });
    }

    @Test
    void liveRequestWithoutExplicitApprovalFailsClosed() {
        qa().withPropertyValues("app.qa-media.live-enabled=true", "app.qa-media.cloud-name=isolated-qa-cloud",
                        "app.qa-media.api-key=123456789012", "app.qa-media.api-secret=nonfunctional-test-secret",
                        "cloudinary.cloud-name=isolated-qa-cloud")
                .run(context -> assertThat(context).hasFailed());
    }

    @Test
    void liveRequestWithoutDedicatedQaCredentialsFailsClosedWithoutGenericFallback() {
        qa().withPropertyValues("app.qa-media.live-enabled=true", "app.qa-media.live-approved=true",
                        "cloudinary.cloud-name=production-looking-cloud", "cloudinary.api-key=generic-key",
                        "cloudinary.api-secret=generic-secret")
                .run(context -> assertThat(context).hasFailed());
    }

    @Test
    void liveRequestRejectsPlaceholderCredential() {
        qa().withPropertyValues("app.qa-media.live-enabled=true", "app.qa-media.live-approved=true",
                        "app.qa-media.cloud-name=isolated-qa-cloud", "app.qa-media.api-key=your_api_key",
                        "app.qa-media.api-secret=nonfunctional-test-secret", "cloudinary.cloud-name=isolated-qa-cloud")
                .run(context -> assertThat(context).hasFailed());
    }

    @Test
    void qaApprovalSettingOutsideQaIsRejected() {
        runner.withPropertyValues("QA_MEDIA_LIVE_APPROVED=true")
                .run(context -> assertThat(context).hasFailed());
    }

    @Test
    void qaProfileDefaultsAndDedicatedEnvironmentBindingsResolveToTheValidatedIdentity() throws Exception {
        java.util.Properties qaProperties = new java.util.Properties();
        try (var input = getClass().getClassLoader().getResourceAsStream("application-qa.properties")) {
            qaProperties.load(input);
        }
        MockEnvironment environment = new MockEnvironment();
        environment.setActiveProfiles("qa");
        environment.getPropertySources().addFirst(new PropertiesPropertySource("qa-profile", qaProperties));
        assertThat(QaMediaActivation.resolve(environment).mode()).isEqualTo(QaMediaActivation.Mode.QA_DISABLED);

        environment.getPropertySources().addFirst(new MapPropertySource("qa-test-injected-settings", java.util.Map.of(
                "APP_QA_MEDIA_LIVE_ENABLED", "true",
                "QA_MEDIA_LIVE_APPROVED", "true",
                "QA_CLOUDINARY_CLOUD_NAME", "isolated-qa-cloud",
                "QA_CLOUDINARY_API_KEY", "123456789012",
                "QA_CLOUDINARY_API_SECRET", "nonfunctional-test-secret")));
        QaMediaActivation activation = QaMediaActivation.resolve(environment);
        assertThat(activation.mode()).isEqualTo(QaMediaActivation.Mode.QA_LIVE);
        assertThat(environment.getProperty("cloudinary.cloud-name")).isEqualTo(activation.cloudName());
    }

    @Test
    void liveRequestRejectsSentinelCloudName() {
        qa().withPropertyValues("app.qa-media.live-enabled=true", "app.qa-media.live-approved=true",
                        "app.qa-media.cloud-name=qa-disabled", "app.qa-media.api-key=123456789012",
                        "app.qa-media.api-secret=nonfunctional-test-secret", "cloudinary.cloud-name=qa-disabled")
                .run(context -> assertThat(context).hasFailed());
    }

    @Test
    void liveRequestRejectsCloudIdentityMismatchBetweenLifecycleAndProvider() {
        qa().withPropertyValues("app.qa-media.live-enabled=true", "app.qa-media.live-approved=true",
                        "app.qa-media.cloud-name=isolated-qa-cloud", "app.qa-media.api-key=123456789012",
                        "app.qa-media.api-secret=nonfunctional-test-secret", "cloudinary.cloud-name=other-cloud")
                .run(context -> assertThat(context).hasFailed());
    }

    @Test
    void duplicateStorageImplementationsFailContextInitialization() {
        qa().withUserConfiguration(DuplicateStorageConfiguration.class)
                .run(context -> assertThat(context).hasFailed());
    }

    @Test
    void mixedQaAndNonQaProfilesAreRejected() {
        runner.withInitializer(context -> context.getEnvironment().setActiveProfiles("qa", "local"))
                .run(context -> assertThat(context).hasFailed());
    }

    @Test
    void qaNeverFallsBackToGenericCloudinaryCredentials() {
        qa().withPropertyValues("cloudinary.cloud-name=generic-cloud", "cloudinary.api-key=generic-key",
                        "cloudinary.api-secret=generic-secret")
                .run(context -> {
                    assertThat(context).hasNotFailed();
                    assertThat(context).doesNotHaveBean(Cloudinary.class);
                    assertThat(context.getBean(MediaStorageService.class)).isInstanceOf(QaDisabledMediaStorageService.class);
                });
    }

    private ApplicationContextRunner qa() {
        return runner.withInitializer(context -> context.getEnvironment().setActiveProfiles("qa"));
    }

    @Configuration(proxyBeanMethods = false)
    @EnableConfigurationProperties({CloudinaryIdentityProperties.class, MediaStorageProperties.class})
    @Import({QaMediaModeConfiguration.class, CloudinaryConfig.class, MediaStorageConfiguration.class})
    static class MediaConfiguration {
    }

    @Configuration(proxyBeanMethods = false)
    static class DuplicateStorageConfiguration {
        @Bean
        MediaStorageService duplicateStorage() {
            return new MediaStorageService() {
                @Override public MediaUploadResult upload(MediaUploadIntent intent, ValidatedMedia media) {
                    throw new MediaStorageException(MediaStorageException.Reason.PROVIDER_UNAVAILABLE);
                }
                @Override public DeleteResult delete(MediaAssetIdentity identity) {
                    throw new MediaStorageException(MediaStorageException.Reason.PROVIDER_UNAVAILABLE);
                }
            };
        }
    }
}
