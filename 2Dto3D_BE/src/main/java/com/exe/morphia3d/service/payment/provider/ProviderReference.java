package com.exe.morphia3d.service.payment.provider;

import com.exe.morphia3d.enums.PaymentProvider;

import java.util.UUID;

/** Stable provider-safe identity derived from the immutable local attempt id. */
public final class ProviderReference {
    private ProviderReference() {}

    public static String forAttempt(PaymentProvider provider, UUID attemptId) {
        if (provider == null || provider == PaymentProvider.UNKNOWN || attemptId == null) {
            throw new IllegalArgumentException("A supported provider and attempt id are required");
        }
        String compact = attemptId.toString().replace("-", "");
        if (compact.length() > 50) throw new IllegalStateException("Provider reference exceeds supported length");
        return compact;
    }
}
