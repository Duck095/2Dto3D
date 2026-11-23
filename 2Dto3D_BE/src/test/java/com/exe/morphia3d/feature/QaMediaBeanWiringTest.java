package com.exe.morphia3d.feature;

import com.cloudinary.Cloudinary;
import com.exe.morphia3d.config.CloudinaryConfig;
import com.exe.morphia3d.config.CloudinaryIdentityProperties;
import com.exe.morphia3d.config.MediaStorageConfiguration;
import com.exe.morphia3d.config.QaMediaModeConfiguration;
import com.exe.morphia3d.repository.MediaAssetRepository;
import com.exe.morphia3d.repository.Model3dRepository;
import com.exe.morphia3d.repository.ModelImageRepository;
import com.exe.morphia3d.repository.ProductGlbStructureSnapshotRepository;
import com.exe.morphia3d.repository.ProductMeshConfigurationRevisionRepository;
import com.exe.morphia3d.repository.ProductPrintAssetRepository;
import com.exe.morphia3d.service.MediaAssetLifecycleService;
import com.exe.morphia3d.service.MediaLifecycleProperties;
import com.exe.morphia3d.service.MediaStorageProperties;
import com.exe.morphia3d.service.MediaStorageService;
import com.exe.morphia3d.service.impl.QaDisabledMediaStorageService;
import com.exe.morphia3d.service.impl.CloudinaryMediaStorageService;
import org.junit.jupiter.api.Test;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Import;
import jakarta.persistence.EntityManagerFactory;
import jakarta.persistence.EntityManager;

import javax.sql.DataSource;
import java.time.Clock;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;

class QaMediaBeanWiringTest {
    @Test
    void lifecycleAndQaStorageInstantiateWithoutCloudinaryCredentialsOrDatasource() {
        new ApplicationContextRunner()
                .withUserConfiguration(QaMediaGraph.class)
                .withInitializer(context -> context.getEnvironment().setActiveProfiles("qa"))
                .withPropertyValues("cloudinary.cloud-name=qa-disabled")
                .run(context -> {
                    assertThat(context).hasNotFailed();
                    assertThat(context).hasSingleBean(MediaAssetLifecycleService.class);
                    assertThat(context).hasSingleBean(MediaStorageService.class);
                    assertThat(context.getBean(MediaStorageService.class))
                            .isInstanceOf(QaDisabledMediaStorageService.class);
                    assertThat(context).doesNotHaveBean(Cloudinary.class);
                    assertThat(context).doesNotHaveBean(DataSource.class);
                });
    }

    @Test
    void productionCloudinaryClientAndLifecycleUseTheSameConfiguredIdentityValue() {
        CloudinaryIdentityProperties identity = new CloudinaryIdentityProperties();
        identity.setCloudName("configured-production-cloud");
        Cloudinary client = new CloudinaryConfig().cloudinary(identity, new MediaStorageProperties(),
                "test-api-key", "test-api-secret");

        assertThat(client.config.cloudName).isEqualTo(identity.getCloudName());
    }

    @Test
    void approvedQaCloudinaryAndLifecycleShareQaIdentityWithoutDatasource() {
        new ApplicationContextRunner()
                .withUserConfiguration(QaMediaGraph.class)
                .withInitializer(context -> context.getEnvironment().setActiveProfiles("qa"))
                .withPropertyValues("app.qa-media.live-enabled=true", "app.qa-media.live-approved=true",
                        "app.qa-media.cloud-name=isolated-qa-cloud", "app.qa-media.api-key=123456789012",
                        "app.qa-media.api-secret=nonfunctional-test-secret", "cloudinary.cloud-name=isolated-qa-cloud")
                .run(context -> {
                    assertThat(context).hasNotFailed();
                    assertThat(context).hasSingleBean(MediaAssetLifecycleService.class);
                    assertThat(context.getBean(MediaStorageService.class))
                            .isInstanceOf(CloudinaryMediaStorageService.class);
                    assertThat(context.getBean(Cloudinary.class).config.cloudName).isEqualTo(
                            context.getBean(CloudinaryIdentityProperties.class).getCloudName());
                    assertThat(context).doesNotHaveBean(DataSource.class);
                });
    }

    @Configuration(proxyBeanMethods = false)
    @EnableConfigurationProperties({CloudinaryIdentityProperties.class,
            MediaStorageProperties.class, MediaLifecycleProperties.class})
    @Import({MediaAssetLifecycleService.class, QaMediaModeConfiguration.class,
            CloudinaryConfig.class, MediaStorageConfiguration.class})
    static class QaMediaGraph {
        @Bean MediaAssetRepository assets() { return mock(MediaAssetRepository.class); }
        @Bean Model3dRepository models() { return mock(Model3dRepository.class); }
        @Bean ModelImageRepository images() { return mock(ModelImageRepository.class); }
        @Bean ProductGlbStructureSnapshotRepository snapshots() {
            return mock(ProductGlbStructureSnapshotRepository.class);
        }
        @Bean ProductMeshConfigurationRevisionRepository meshRevisions() {
            return mock(ProductMeshConfigurationRevisionRepository.class);
        }
        @Bean ProductPrintAssetRepository printAssets() { return mock(ProductPrintAssetRepository.class); }
        @Bean Clock clock() { return Clock.systemUTC(); }
        @Bean EntityManagerFactory entityManagerFactory() { return mock(EntityManagerFactory.class); }
        @Bean EntityManager entityManager() { return mock(EntityManager.class); }
    }
}
