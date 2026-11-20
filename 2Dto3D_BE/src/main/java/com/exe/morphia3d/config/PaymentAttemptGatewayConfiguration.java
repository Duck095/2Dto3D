package com.exe.morphia3d.config;

import com.exe.morphia3d.enums.PaymentProvider;
import com.exe.morphia3d.domain.payment.PaymentAttemptTransitionPolicy;
import com.exe.morphia3d.domain.payment.PaymentReadinessPolicy;
import com.exe.morphia3d.service.PaymentAttemptGateway;
import com.exe.morphia3d.service.payment.payos.PayosGateway;
import com.exe.morphia3d.service.payment.UnavailablePaymentAttemptGateway;
import org.springframework.context.annotation.Bean;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Primary;
import org.springframework.core.env.Environment;
import org.springframework.boot.context.properties.bind.Bindable;
import org.springframework.boot.context.properties.bind.Binder;

import java.time.Clock;
import java.util.Map;

@Configuration
@EnableConfigurationProperties(PayosMerchantBindingProperties.class)
public class PaymentAttemptGatewayConfiguration {
    @Bean("payosAttemptGateway")
    PaymentAttemptGateway payosAttemptGateway() {
        return new UnavailablePaymentAttemptGateway(PaymentProvider.PAYOS);
    }

    @Bean
    @Primary
    Clock paymentClock() { return Clock.systemUTC(); }

    @Bean
    PaymentAttemptTransitionPolicy paymentAttemptTransitionPolicy() { return new PaymentAttemptTransitionPolicy(); }

    @Bean
    PaymentReadinessPolicy paymentReadinessPolicy() { return new PaymentReadinessPolicy(); }

    @Bean("payosWebhookChecksumKeys")
    Map<String, String> payosWebhookChecksumKeys(Environment environment) {
        return Binder.get(environment).bind("app.payment.payos.webhook-checksum-keys",
                Bindable.mapOf(String.class, String.class)).orElse(Map.of());
    }

    @Bean("payosQueryGateways")
    Map<String, PayosGateway> payosQueryGateways() { return Map.of(); }
}
