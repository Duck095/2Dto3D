package com.exe.morphia3d.service.payment;

import java.util.Map;

public interface PaymentGatewayService {
    String createPaymentUrl(Integer orderId, Double amount, String ipAddress);
    boolean verifyReturnUrl(Map<String, String> params);
}