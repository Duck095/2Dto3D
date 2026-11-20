package com.exe.morphia3d.service;

import com.exe.morphia3d.domain.payment.PaymentAttemptEvent;
import com.exe.morphia3d.enums.PaymentProvider;

import java.time.Instant;
import java.util.UUID;

/** Internal event supplied only after an authorized provider verifier has authenticated it. */
public record VerifiedPaymentEvent(
        PaymentProvider provider,
        UUID attemptId,
        String merchantReference,
        String providerEventKey,
        String eventDigest,
        long amountVnd,
        String providerTransactionId,
        PaymentAttemptEvent event,
        Instant verifiedAt,
        String verificationSource) {
}
