package com.exe.morphia3d.feature;

import com.exe.morphia3d.service.payment.PaymentGatewayService;

import java.util.Map;

public final class DisabledPaymentGatewayService implements PaymentGatewayService {
    @Override
    public String createPaymentUrl(Integer orderId, Double amount, String ipAddress) {
        throw new FeatureDisabledException(FeatureFamily.PAYMENT);
    }

    @Override
    public boolean verifyReturnUrl(Map<String, String> params) {
        throw new FeatureDisabledException(FeatureFamily.PAYMENT);
    }
}
