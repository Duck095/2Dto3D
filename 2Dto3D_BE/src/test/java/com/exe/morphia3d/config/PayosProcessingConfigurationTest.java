package com.exe.morphia3d.config;

import com.exe.morphia3d.repository.OrderRepository;
import com.exe.morphia3d.repository.PaymentAttemptRepository;
import com.exe.morphia3d.repository.PayosPaymentLinkRepository;
import com.exe.morphia3d.repository.PayosReconciliationQueryRepository;
import com.exe.morphia3d.repository.PayosWebhookReceiptRepository;
import com.exe.morphia3d.service.PaymentAttemptLifecycleService;
import com.exe.morphia3d.service.PaymentAttemptPersistenceService;
import com.exe.morphia3d.service.PaymentAttemptStateService;
import com.exe.morphia3d.service.PaymentReadinessService;
import com.exe.morphia3d.service.PaymentAttemptGateway;
import com.exe.morphia3d.service.PayosReconciliationProcessingService;
import com.exe.morphia3d.service.PayosWebhookProcessingService;
import com.exe.morphia3d.service.impl.PaymentServiceImpl;
import com.exe.morphia3d.service.payment.payos.PayosGateway;
import com.exe.morphia3d.service.payment.payos.PayosPaymentPersistenceService;
import com.exe.morphia3d.service.payment.payos.PayosSensitiveDataCipher;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Import;

import java.time.Clock;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;

class PayosProcessingConfigurationTest {
    private final ApplicationContextRunner contextRunner = new ApplicationContextRunner()
            .withUserConfiguration(PayosSensitiveDataConfiguration.class,
                    PayosProcessingConfiguration.class, DependencyFixtures.class);

    @Test
    void payosServicesAreAbsentWhenEncryptionKeyIsNotConfigured() {
        contextRunner.run(context -> {
            assertThat(context).hasNotFailed();
            assertThat(context).doesNotHaveBean(PayosSensitiveDataCipher.class);
            assertThat(context).doesNotHaveBean(PayosPaymentPersistenceService.class);
            assertThat(context).doesNotHaveBean(PayosWebhookProcessingService.class);
            assertThat(context).doesNotHaveBean(PayosReconciliationProcessingService.class);
                    assertThat(context).hasSingleBean(PaymentServiceImpl.class);
        });
    }

    @Test
    void payosServicesAreRegisteredTogetherWithAValidTestKey() {
        contextRunner.withPropertyValues(
                "app.payment.payos.encryption-key-version=1",
                "app.payment.payos.encryption-keyring[1]=AAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAA=")
                .run(context -> {
                    assertThat(context).hasNotFailed();
                    assertThat(context).hasSingleBean(PayosSensitiveDataCipher.class);
                    assertThat(context).hasSingleBean(PayosPaymentPersistenceService.class);
                    assertThat(context).hasSingleBean(PayosWebhookProcessingService.class);
                    assertThat(context).hasSingleBean(PayosReconciliationProcessingService.class);
                    assertThat(context).hasSingleBean(PaymentServiceImpl.class);
                });
    }

    @Configuration(proxyBeanMethods = false)
    @Import(PaymentServiceImpl.class)
    static class DependencyFixtures {
        @Bean PaymentAttemptRepository paymentAttemptRepository() { return mock(PaymentAttemptRepository.class); }
        @Bean OrderRepository orderRepository() { return mock(OrderRepository.class); }
        @Bean PayosPaymentLinkRepository payosPaymentLinkRepository() { return mock(PayosPaymentLinkRepository.class); }
        @Bean PayosWebhookReceiptRepository payosWebhookReceiptRepository() { return mock(PayosWebhookReceiptRepository.class); }
        @Bean PayosReconciliationQueryRepository payosReconciliationQueryRepository() { return mock(PayosReconciliationQueryRepository.class); }
        @Bean JdbcTemplate jdbcTemplate() { return mock(JdbcTemplate.class); }
        @Bean PaymentAttemptStateService paymentAttemptStateService() { return mock(PaymentAttemptStateService.class); }
        @Bean PaymentAttemptPersistenceService paymentAttemptPersistenceService() { return mock(PaymentAttemptPersistenceService.class); }
        @Bean PaymentAttemptLifecycleService paymentAttemptLifecycleService() { return mock(PaymentAttemptLifecycleService.class); }
        @Bean PaymentReadinessService paymentReadinessService() { return mock(PaymentReadinessService.class); }
        @Bean com.exe.morphia3d.feature.FeatureGate featureGate() { return mock(com.exe.morphia3d.feature.FeatureGate.class); }
        @Bean Clock clock() { return Clock.systemUTC(); }
        @Bean("payosWebhookChecksumKeys") Map<String, String> payosWebhookChecksumKeys() { return Map.of(); }
        @Bean("payosQueryGateways") Map<String, PayosGateway> payosQueryGateways() { return Map.of(); }

    }
}
