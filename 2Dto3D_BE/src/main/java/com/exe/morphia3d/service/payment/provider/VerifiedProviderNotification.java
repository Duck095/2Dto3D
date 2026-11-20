package com.exe.morphia3d.service.payment.provider;

import com.exe.morphia3d.service.VerifiedPaymentEvent;
import com.exe.morphia3d.enums.PaymentProvider;

import java.time.Instant;
import java.util.UUID;

public record VerifiedProviderNotification(PaymentProvider provider, UUID attemptId, String merchantReference,
                                           String providerRequestId, String merchantId, ProviderPaymentState state,
                                           String providerEventKey, String eventDigest, String providerTransactionId,
                                           long amountVnd, Instant verifiedAt, String verificationSource,
                                           VerifiedPaymentEvent lifecycleEvent) {
}
