package com.exe.morphia3d.feature;

import com.exe.morphia3d.config.CloudinaryConfig;
import com.exe.morphia3d.config.FeatureGateConfiguration;
import com.exe.morphia3d.config.QaDisabledProviderConfiguration;
import com.exe.morphia3d.config.MomoConfig;
import com.exe.morphia3d.config.VnPayConfig;
import com.exe.morphia3d.service.MediaAssetReconciler;
import com.exe.morphia3d.service.impl.CloudinaryMediaStorageService;
import com.exe.morphia3d.service.impl.CloudinaryServiceImpl;
import com.exe.morphia3d.service.impl.QaDisabledMediaStorageService;
import com.exe.morphia3d.service.payment.MomoService;
import com.exe.morphia3d.service.payment.VnPayService;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.core.io.ClassPathResource;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Service;
import org.springframework.core.annotation.AnnotatedElementUtils;

import java.io.IOException;
import java.util.Properties;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class QaIsolationConfigurationTest {
    @Test
    void restrictedFeaturesDefaultOffAndRemainIndependent() {
        FeatureGateProperties properties = new FeatureGateProperties();
        assertThat(properties.anyEnabled()).isFalse();
        properties.getPayment().setEnabled(true);
        FeatureGate gate = new FeatureGate(properties);
        assertThat(gate.isEnabled(FeatureFamily.PAYMENT)).isTrue();
        for (FeatureFamily family : FeatureFamily.values()) {
            if (family != FeatureFamily.PAYMENT) assertThat(gate.isEnabled(family)).isFalse();
        }
    }

    @Test
    void qaPropertiesRequireDedicatedCredentialsAndDisableDatabaseMutationAndFeatures() throws IOException {
        Properties properties = new Properties();
        try (var stream = new ClassPathResource("application-qa.properties").getInputStream()) {
            properties.load(stream);
        }
        assertThat(properties.getProperty("spring.datasource.url")).isEqualTo("${QA_DB_URL}");
        assertThat(properties.getProperty("spring.datasource.username")).isEqualTo("${QA_DB_USERNAME}");
        assertThat(properties.getProperty("spring.datasource.password")).isEqualTo("${QA_DB_PASSWORD}");
        assertThat(properties.getProperty("jwt.secret")).isEqualTo("${QA_JWT_SECRET}");
        assertThat(properties.getProperty("spring.flyway.enabled")).isEqualTo("false");
        assertThat(properties.getProperty("spring.jpa.hibernate.ddl-auto")).isEqualTo("validate");
        assertThat(properties.getProperty("app.features.admin-product-snapshot-upload.enabled")).isEqualTo("false");
        assertThat(properties.getProperty("media.lifecycle.reconciliation-enabled")).isEqualTo("false");
        assertThat(properties.getProperty("app.qa-media.live-enabled"))
                .isEqualTo("${APP_QA_MEDIA_LIVE_ENABLED:false}");
        assertThat(properties.getProperty("app.qa-media.live-approved"))
                .isEqualTo("${QA_MEDIA_LIVE_APPROVED:false}");
        assertThat(properties.getProperty("app.qa-media.cloud-name"))
                .isEqualTo("${QA_CLOUDINARY_CLOUD_NAME:qa-disabled}");
        for (FeatureFamily family : FeatureFamily.values()) {
            assertThat(properties.getProperty("app.features." + family.name().toLowerCase() + ".enabled"))
                    .isEqualTo("false");
        }
        assertThat(properties.containsKey("spring.datasource.url")).isTrue();
        assertThat(properties.containsKey("cloudinary.api-key")).isFalse();
        assertThat(properties.containsKey("cloudinary.api-secret")).isFalse();
        assertThat(properties.stringPropertyNames()).noneMatch(key -> key.startsWith("momo.") || key.startsWith("vnpay."));
    }

    @Test
    void qaProfileRejectsAnyAttemptToEnableRestrictedFeatures() {
        new ApplicationContextRunner()
                .withUserConfiguration(FeatureGateConfiguration.class)
                .withInitializer(context -> context.getEnvironment().setActiveProfiles("qa"))
                .withPropertyValues("app.features.payment.enabled=true")
                .run(context -> assertThat(context).hasFailed());
        new ApplicationContextRunner()
                .withUserConfiguration(FeatureGateConfiguration.class)
                .withInitializer(context -> context.getEnvironment().setActiveProfiles("qa"))
                .withPropertyValues("app.features.admin-product-snapshot-upload.enabled=true")
                .run(context -> assertThat(context).hasFailed());
    }

    @Test
    void snapshotUploadGateDefaultsOffAndDoesNotJoinGlobalFeatureFamilies() {
        FeatureGateProperties properties = new FeatureGateProperties();
        FeatureGate gate = new FeatureGate(properties);
        assertThat(gate.isAdminProductSnapshotUploadEnabled()).isFalse();
        assertThat(properties.anyEnabled()).isFalse();
        properties.getAdminProductSnapshotUpload().setEnabled(true);
        assertThat(gate.isAdminProductSnapshotUploadEnabled()).isTrue();
        assertThat(properties.anyEnabled()).isFalse();
    }

    @Test
    void qaPaymentAdaptersAreLocalFailClosedBeansWithoutProviderCredentials() {
        new ApplicationContextRunner()
                .withUserConfiguration(QaDisabledProviderConfiguration.class)
                .withInitializer(context -> context.getEnvironment().setActiveProfiles("qa"))
                .run(context -> {
                    assertThat(context).hasNotFailed();
                    assertThat(context).hasBean("vnPayService").hasBean("momoService");
                    assertThat(context.getBean("vnPayService")).isInstanceOf(DisabledPaymentGatewayService.class);
                    assertThat(context.getBean("momoService")).isInstanceOf(DisabledPaymentGatewayService.class);
                });
    }

    @Test
    void providerTypesAreNotComponentScannedOutsideValidatedModeSelection() {
        assertThat(CloudinaryMediaStorageService.class.isAnnotationPresent(Service.class)).isFalse();
        assertThat(QaDisabledMediaStorageService.class.isAnnotationPresent(Service.class)).isFalse();
        assertThat(java.util.Arrays.stream(CloudinaryConfig.class.getDeclaredMethods())
                .filter(method -> method.isAnnotationPresent(org.springframework.context.annotation.Bean.class))
                .allMatch(method -> AnnotatedElementUtils.findMergedAnnotation(method,
                        org.springframework.context.annotation.Conditional.class) != null)).isTrue();
        org.springframework.context.annotation.Profile qaProviders = org.springframework.core.annotation.AnnotatedElementUtils
                .findMergedAnnotation(QaDisabledProviderConfiguration.class, org.springframework.context.annotation.Profile.class);
        assertThat(qaProviders).isNotNull();
        assertThat(qaProviders.value()).containsExactly("qa");

        ConditionalOnProperty reconciler = AnnotatedElementUtils.findMergedAnnotation(
                MediaAssetReconciler.class, ConditionalOnProperty.class);
        assertThat(reconciler).isNotNull();
        assertThat(reconciler.havingValue()).isEqualTo("true");
        assertThat(reconciler.matchIfMissing()).isTrue();
    }

    @Test
    void qaMediaStorageFailsClosedWithoutProviderCalls() {
        QaDisabledMediaStorageService storage = new QaDisabledMediaStorageService();
        assertThatThrownBy(() -> storage.upload(null, null))
                .isInstanceOf(com.exe.morphia3d.service.MediaStorageException.class);
        assertThatThrownBy(() -> storage.delete(null))
                .isInstanceOf(com.exe.morphia3d.service.MediaStorageException.class);
    }
}
