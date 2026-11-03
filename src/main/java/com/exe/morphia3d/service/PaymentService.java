package com.exe.morphia3d.service;

import com.exe.morphia3d.dto.request.CreatePaymentRequest;
import com.exe.morphia3d.dto.response.PaymentUrlResponse;

import java.util.Map;

public interface PaymentService {
    PaymentUrlResponse createPayment(Integer authenticatedUserId, CreatePaymentRequest request, String ipAddress);
    void handleVnPayReturn(Map<String, String> params);
    void handleMomoReturn(Map<String, String> params);
}
