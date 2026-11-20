package com.exe.morphia3d.config;

import com.exe.morphia3d.service.PaymentAttemptGateway;
import com.exe.morphia3d.service.payment.payos.PayosGateway;
import com.exe.morphia3d.service.payment.payos.PayosPaymentPersistenceService;
import com.exe.morphia3d.service.payment.payos.PayosProtocol;
import com.exe.morphia3d.service.payment.payos.PayosTestOnlyPaymentAttemptGateway;
import com.exe.morphia3d.service.payment.payos.PayosTransport;
import org.springframework.beans.factory.config.ConfigurableListableBeanFactory;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.core.Ordered;
import org.springframework.core.PriorityOrdered;
import org.springframework.beans.factory.support.BeanDefinitionRegistry;
import org.springframework.beans.factory.support.BeanDefinitionRegistryPostProcessor;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.atomic.AtomicInteger;

/** Test classpath only: swaps the fail-closed default for an offline fake transport. */
@TestConfiguration(proxyBeanMethods = false)
@Import(PayosFinalAcceptanceConfiguration.FakeTransportConfiguration.class)
public class PayosFinalAcceptanceConfiguration {
    public static final String CHANNEL = "test-merchant-channel";
    public static final String CHECKSUM = "1a54716c8f0efb2744fb28b6e38b25da7f67a925d98bc1c18bd8faaecadd7675";

    @Bean
    static BeanDefinitionRegistryPostProcessor removeFailClosedGatewayDefinition() {
        return new RemoveFailClosedGatewayDefinition();
    }

    @Bean("payosTestOnlyAcceptanceGateway")
    PaymentAttemptGateway payosTestOnlyAcceptanceGateway(PayosGateway provider,
            PayosPaymentPersistenceService persistence, Clock paymentClock) {
        return new PayosTestOnlyPaymentAttemptGateway(true, CHANNEL,
                "https://shop.example.test/cancel", "https://shop.example.test/return",
                Set.of("pay.payos.vn"), Set.of("shop.example.test"), Duration.ofMinutes(20),
                provider, persistence, paymentClock);
    }

    @Bean
    static AtomicInteger payosCreateCalls() { return new AtomicInteger(); }

    @TestConfiguration(proxyBeanMethods = false)
    static class FakeTransportConfiguration {
        @Bean
        PayosGateway payosGateway(AtomicInteger payosCreateCalls) {
            PayosTransport transport = new PayosTransport() {
                @Override
                public HttpResponse createPaymentLink(String endpoint, String clientId, String apiKey,
                        Map<String, Object> request, Duration timeout) {
                    payosCreateCalls.incrementAndGet();
                    Map<String, Object> data = new LinkedHashMap<>();
                    data.put("bin", "970422");
                    data.put("accountNumber", "123456789");
                    data.put("accountName", "TEST_ONLY ACCOUNT");
                    data.put("amount", request.get("amount"));
                    data.put("description", request.get("description"));
                    data.put("orderCode", request.get("orderCode"));
                    data.put("currency", "VND");
                    data.put("paymentLinkId", "TEST_ONLY_LINK_" + request.get("orderCode"));
                    data.put("status", "PENDING");
                    data.put("checkoutUrl", "https://pay.payos.vn/test-only/" + request.get("orderCode"));
                    data.put("qrCode", "TEST_ONLY_FAKE_QR_" + request.get("orderCode"));
                    String body = "{\"code\":\"00\",\"desc\":\"TEST_ONLY\",\"data\":"
                            + new com.fasterxml.jackson.databind.ObjectMapper().valueToTree(data)
                            + ",\"signature\":\"" + PayosProtocol.signData(data, CHECKSUM) + "\"}";
                    return new HttpResponse(200, body);
                }

                @Override
                public HttpResponse getPaymentLink(String endpoint, String clientId, String apiKey,
                        long orderCode, Duration timeout) {
                    throw new AssertionError("TEST_ONLY acceptance must not issue provider queries");
                }
            };
            return new PayosGateway("https://api-merchant.payos.vn", "TEST_ONLY_CLIENT_ID",
                    "TEST_ONLY_API_KEY", CHECKSUM, Set.of("pay.payos.vn"), Duration.ofSeconds(2), transport);
        }
    }

    private static final class RemoveFailClosedGatewayDefinition implements BeanDefinitionRegistryPostProcessor, PriorityOrdered {
        @Override
        public void postProcessBeanDefinitionRegistry(BeanDefinitionRegistry registry) {
            if (registry.containsBeanDefinition("payosAttemptGateway")) {
                registry.removeBeanDefinition("payosAttemptGateway");
            }
        }

        @Override public void postProcessBeanFactory(ConfigurableListableBeanFactory beanFactory) { }
        @Override public int getOrder() { return Ordered.LOWEST_PRECEDENCE; }
    }
}
