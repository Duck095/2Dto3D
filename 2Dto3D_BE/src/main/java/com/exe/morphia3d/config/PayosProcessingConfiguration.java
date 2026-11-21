package com.exe.morphia3d.config;

import com.exe.morphia3d.repository.OrderRepository;
import com.exe.morphia3d.repository.PaymentAttemptRepository;
import com.exe.morphia3d.repository.PayosPaymentLinkRepository;
import com.exe.morphia3d.repository.PayosReconciliationQueryRepository;
import com.exe.morphia3d.repository.PayosWebhookReceiptRepository;
import com.exe.morphia3d.service.PaymentAttemptLifecycleService;
import com.exe.morphia3d.service.PaymentAttemptPersistenceService;
import com.exe.morphia3d.service.PaymentAttemptStateService;
import com.exe.morphia3d.service.PayosReconciliationProcessingService;
import com.exe.morphia3d.service.PayosWebhookProcessingService;
import com.exe.morphia3d.service.payment.payos.PayosGateway;
import com.exe.morphia3d.service.payment.payos.PayosPaymentPersistenceService;
import com.exe.morphia3d.service.payment.payos.PayosSensitiveDataCipher;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.jdbc.core.JdbcTemplate;

import java.time.Clock;
import java.util.Map;

/** Registers the PayOS persistence and processing graph only when its encryption key is configured. */
@Configuration(proxyBeanMethods = false)
@ConditionalOnProperty(name = "app.payment.payos.encryption-key-version")
public class PayosProcessingConfiguration {
    @Bean
    PayosPaymentPersistenceService payosPaymentPersistenceService(
            PaymentAttemptRepository attempts,
            OrderRepository orders,
            PayosPaymentLinkRepository links,
            PayosWebhookReceiptRepository receipts,
            PayosReconciliationQueryRepository reconciliationQueries,
            JdbcTemplate jdbc,
            PayosSensitiveDataCipher cipher,
            PaymentAttemptStateService stateService,
            Clock clock) {
        return new PayosPaymentPersistenceService(attempts, orders, links, receipts, reconciliationQueries,
                jdbc, cipher, stateService, clock);
    }

    @Bean
    PayosWebhookProcessingService payosWebhookProcessingService(
            @Qualifier("payosWebhookChecksumKeys") Map<String, String> checksumKeysByChannel,
            PayosPaymentPersistenceService persistence,
            PaymentAttemptPersistenceService attempts,
            PaymentAttemptLifecycleService lifecycle) {
        return new PayosWebhookProcessingService(checksumKeysByChannel, persistence, attempts, lifecycle);
    }

    @Bean
    PayosReconciliationProcessingService payosReconciliationProcessingService(
            @Qualifier("payosQueryGateways") Map<String, PayosGateway> gatewaysByChannel,
            PayosPaymentPersistenceService persistence,
            PaymentAttemptPersistenceService attempts,
            PaymentAttemptLifecycleService lifecycle) {
        return new PayosReconciliationProcessingService(gatewaysByChannel, persistence, attempts, lifecycle);
    }
}
