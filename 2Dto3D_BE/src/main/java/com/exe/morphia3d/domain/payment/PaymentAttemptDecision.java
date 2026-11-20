package com.exe.morphia3d.domain.payment;

import java.util.Objects;

/** A policy result only; persistence, reconciliation case creation, and provider calls belong to integrations. */
public record PaymentAttemptDecision(
        PaymentAttemptStatus status,
        PaymentAttemptDisposition disposition,
        boolean advanceOrderToProcessing) {

    public PaymentAttemptDecision {
        Objects.requireNonNull(status, "status is required");
        Objects.requireNonNull(disposition, "disposition is required");
        if (advanceOrderToProcessing
                && (status != PaymentAttemptStatus.SUCCEEDED
                || disposition != PaymentAttemptDisposition.APPLIED)) {
            throw new IllegalArgumentException("Only an applied success may advance a payable order");
        }
    }
}
