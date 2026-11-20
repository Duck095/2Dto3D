package com.exe.morphia3d.domain.payment;

/**
 * Normalized provider or confirmed refund outcome presented to the pure transition policy.
 * Refund events report completed outcomes; they never initiate a refund.
 */
public enum PaymentAttemptEvent {
    SUBMISSION_CLAIMED,
    PROVIDER_ACCEPTED,
    AMBIGUOUS_RESULT,
    SUCCESS,
    FAILURE,
    CANCEL,
    EXPIRE,
    PARTIAL_REFUND,
    FULL_REFUND
}
