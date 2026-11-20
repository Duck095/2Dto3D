package com.exe.morphia3d.service;

import java.util.UUID;

public record PaymentProviderRequest(
        UUID attemptId,
        Integer orderId,
        long amountVnd,
        String idempotencyKey,
        String merchantReference) {
}
