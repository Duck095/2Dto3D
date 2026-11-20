package com.exe.morphia3d.service;

import com.exe.morphia3d.dto.request.CreatePaymentAttemptRequest;
import com.exe.morphia3d.dto.response.PaymentAttemptResponse;
import com.exe.morphia3d.dto.response.PaymentReadinessResponse;

import java.util.UUID;

public interface PaymentService {
    PaymentReadinessResponse getReadiness(Integer authenticatedUserId, Integer orderId);
    PaymentAttemptResponse createAttempt(Integer authenticatedUserId, CreatePaymentAttemptRequest request,
                                         String idempotencyKey);
    PaymentAttemptResponse getAttempt(Integer authenticatedUserId, UUID attemptId);
}
