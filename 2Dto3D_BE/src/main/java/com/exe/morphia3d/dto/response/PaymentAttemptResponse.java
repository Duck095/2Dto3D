package com.exe.morphia3d.dto.response;

import com.exe.morphia3d.domain.payment.PaymentAttemptStatus;
import com.exe.morphia3d.enums.PaymentProvider;

import java.util.UUID;

public record PaymentAttemptResponse(
        UUID attemptId,
        Integer orderId,
        PaymentProvider provider,
        Long amountVnd,
        PaymentAttemptStatus status,
        boolean retryAllowed,
        String redirectUrl,
        String errorCode,
        PayosPaymentInfoResponse payosPayment) {
    public PaymentAttemptResponse(UUID attemptId, Integer orderId, PaymentProvider provider, Long amountVnd,
            PaymentAttemptStatus status, boolean retryAllowed, String redirectUrl, String errorCode) {
        this(attemptId, orderId, provider, amountVnd, status, retryAllowed, redirectUrl, errorCode, null);
    }
}
