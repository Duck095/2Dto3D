package com.exe.morphia3d.service.payment.provider;

import com.exe.morphia3d.enums.PaymentProvider;

import java.util.UUID;

public record ProviderAttemptContext(UUID attemptId, PaymentProvider provider, String merchantReference,
                                     long amountVnd, String merchantId, String providerRequestId) {
    public ProviderAttemptContext {
        if (attemptId == null || provider == null || provider == PaymentProvider.UNKNOWN || merchantReference == null
                || merchantReference.isBlank() || amountVnd <= 0 || merchantId == null || merchantId.isBlank()) {
            throw new IllegalArgumentException("Verified attempt context is incomplete");
        }
    }
}
