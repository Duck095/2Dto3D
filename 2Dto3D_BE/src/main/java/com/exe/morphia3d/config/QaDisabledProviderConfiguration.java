package com.exe.morphia3d.config;

import com.exe.morphia3d.feature.DisabledPaymentGatewayService;
import com.exe.morphia3d.service.payment.PaymentGatewayService;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Profile;

@Configuration
@Profile("qa")
public class QaDisabledProviderConfiguration {
    @Bean("vnPayService")
    public PaymentGatewayService qaVnPayService() {
        return new DisabledPaymentGatewayService();
    }

    @Bean("momoService")
    public PaymentGatewayService qaMomoService() {
        return new DisabledPaymentGatewayService();
    }
}
