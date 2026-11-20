package com.exe.morphia3d.domain.payment;

public enum PaymentAttemptStatus {
    INITIATED,
    SUBMITTING,
    PENDING,
    UNKNOWN,
    CANCELED,
    LEGACY_REVIEW,
    SUCCEEDED,
    FAILED,
    EXPIRED,
    PARTIALLY_REFUNDED,
    REFUNDED
}
